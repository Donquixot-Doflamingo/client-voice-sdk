package dev.indicvoice.jvm

import dev.indicvoice.core.AudioSink
import dev.indicvoice.core.AudioSource
import dev.indicvoice.core.Availability
import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.OnDeviceEngineFactory
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.Platform
import dev.indicvoice.core.SttEngine
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TtsEngine
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.TtsResult
import dev.indicvoice.core.VoiceException
import dev.indicvoice.core.VoiceInfo
import dev.indicvoice.core.Wav
import dev.indicvoice.sherpa.SherpaEngineFactory
import dev.indicvoice.sherpa.SherpaRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop platform (Linux / macOS / Windows JVM). Used by the test CLI and the proxy.
 * Tier 1 STT does not exist on desktops; tier 1 TTS uses macOS `say` when present.
 */
class JvmPlatform(
    override val packsDir: File,
    override val logger: Logger = Logger.stdout(),
    private val enableSherpa: Boolean = true,
) : Platform {
    override val name = "jvm-" + System.getProperty("os.name").lowercase().replace(" ", "")
    private val sherpa: OnDeviceEngineFactory? by lazy { if (enableSherpa) SherpaEngineFactory(logger) else null }
    private val macSay: MacSayTtsEngine? by lazy { if (System.getProperty("os.name").lowercase().contains("mac")) MacSayTtsEngine() else null }

    init { if (enableSherpa) SherpaRuntime.prepareJvm(log = logger) }

    override fun osStt(): SttEngine? = null
    override fun osTts(): TtsEngine? = macSay
    override fun onDevice(): OnDeviceEngineFactory? = sherpa
    override fun audioSource(sampleRate: Int): AudioSource? = try { JvmMicrophone(sampleRate) } catch (e: Exception) { logger.warn("no microphone: ${e.message}"); null }
    override fun audioSink(): AudioSink? = JvmSpeaker()
    override fun deviceInfo(): Map<String, Any?> = linkedMapOf(
        "platform" to name, "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"),
        "cpus" to Runtime.getRuntime().availableProcessors(), "maxHeapMb" to Runtime.getRuntime().maxMemory() / 1048576,
        "java" to System.getProperty("java.version"), "sherpa" to SherpaRuntime.VERSION,
    )
}

/** 16-bit mono microphone capture via javax.sound. */
class JvmMicrophone(override val sampleRate: Int) : AudioSource {
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    private val line: TargetDataLine = run {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) throw VoiceException(ErrorCode.NO_ENGINE, "No microphone line supports $sampleRate Hz mono")
        AudioSystem.getLine(info) as TargetDataLine
    }
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(onFrame: (ShortArray) -> Unit) {
        line.open(format, sampleRate / 5 * 2) // 200 ms buffer
        line.start()
        running = true
        thread = Thread({
            val bytes = ByteArray(sampleRate / 50 * 2) // 20 ms frames
            while (running) {
                val n = line.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                val frame = ShortArray(n / 2)
                for (i in frame.indices) frame[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                onFrame(frame)
            }
        }, "indicvoice-mic").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        try { line.stop(); line.close() } catch (_: Exception) {}
        thread?.join(500)
    }
}

class JvmSpeaker : AudioSink {
    @Volatile private var current: SourceDataLine? = null
    override fun play(audio: PcmAudio, onDone: (() -> Unit)?) {
        stop()
        Thread({
            val format = AudioFormat(audio.sampleRate.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
            current = line
            try {
                line.open(format); line.start()
                val bytes = audio.toByteArrayLE()
                line.write(bytes, 0, bytes.size)
                line.drain()
            } catch (e: Exception) { System.err.println("[voice] playback failed: ${e.message}") }
            finally { try { line.close() } catch (_: Exception) {}; if (current === line) current = null; onDone?.invoke() }
        }, "indicvoice-play").apply { isDaemon = true; start() }
    }
    override fun stop() { current?.let { try { it.stop(); it.flush(); it.close() } catch (_: Exception) {} }; current = null }
}

/** Tier-1 TTS on macOS through the built-in `say` command (Lekha etc. for Hindi). */
class MacSayTtsEngine : TtsEngine {
    override val id = "os:macos-say"
    override val tier = Tier.OS
    private val voiceList: List<Triple<String, String, String>> by lazy { listVoices() } // name, locale, sample

    private fun listVoices(): List<Triple<String, String, String>> = try {
        val p = ProcessBuilder("say", "-v", "?").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(5, TimeUnit.SECONDS)
        out.lines().mapNotNull { line ->
            val m = Regex("^(.+?)\\s{2,}([a-zA-Z_\\-]+)\\s+#\\s*(.*)$").find(line.trim()) ?: return@mapNotNull null
            Triple(m.groupValues[1].trim(), m.groupValues[2].replace('_', '-'), m.groupValues[3])
        }
    } catch (e: Exception) { emptyList() }

    private fun voicesFor(language: Language) = voiceList.filter { Language.parse(it.second) == language }

    override fun availability(language: Language): Availability {
        if (voiceList.isEmpty()) return Availability.no("`say` not available")
        val v = voicesFor(language)
        return if (v.isEmpty()) Availability.no("macOS has no ${language.englishName} voice installed (System Settings → Accessibility → Spoken Content)") else Availability(true, null, mapOf("voices" to v.map { it.first }))
    }

    override fun voices(language: Language?): List<VoiceInfo> =
        voiceList.mapNotNull { (name, loc, _) -> Language.parse(loc)?.let { l -> if (language == null || l == language) VoiceInfo("os:$name", l, tier, name, null, true, "os") else null } }

    override fun synthesize(options: TtsOptions): TtsResult {
        val candidates = voicesFor(options.language)
        if (candidates.isEmpty()) throw VoiceException(ErrorCode.LANGUAGE_UNSUPPORTED, "no macOS voice for ${options.language.code}", tier)
        val voice = options.voiceId?.removePrefix("os:")?.takeIf { v -> candidates.any { it.first == v } } ?: candidates[0].first
        val tmp = File.createTempFile("indicvoice-say", ".wav")
        val rate = options.sampleRate ?: 22050
        val t0 = System.currentTimeMillis()
        val p = ProcessBuilder("say", "-v", voice, "-r", (175 * options.speed).toInt().toString(), "-o", tmp.absolutePath, "--data-format=LEI16@$rate", options.text).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) { tmp.delete(); throw VoiceException(ErrorCode.ENGINE_FAILED, "say failed: $log", tier) }
        val audio = Wav.read(tmp); tmp.delete()
        return TtsResult(audio, options.language, "os:$voice", tier, id, System.currentTimeMillis() - t0)
    }
}
