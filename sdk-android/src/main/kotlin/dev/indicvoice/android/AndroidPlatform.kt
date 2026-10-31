package dev.indicvoice.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dev.indicvoice.core.AudioSink
import dev.indicvoice.core.AudioSource
import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.IndicVoice
import dev.indicvoice.core.Logger
import dev.indicvoice.core.OnDeviceEngineFactory
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.Platform
import dev.indicvoice.core.SttEngine
import dev.indicvoice.core.TtsEngine
import dev.indicvoice.core.VoiceConfig
import dev.indicvoice.core.VoiceException
import dev.indicvoice.sherpa.SherpaEngineFactory
import java.io.File

/** Android [Platform]: OS engines, AudioRecord/AudioTrack and the sherpa-onnx tier-2 factory. */
class AndroidPlatform(
    private val context: Context,
    private val config: VoiceConfig,
    override val logger: Logger = AndroidLogger(config.logLevel),
) : Platform {
    override val name = "android"
    override val packsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "indicvoice/packs").also { it.mkdirs() }
    private val sttDelegate = lazy { AndroidOsSttEngine(context, config.stt, config.allowedLanguages, logger) }
    private val ttsDelegate = lazy { AndroidOsTtsEngine(context, config.tts, logger) }
    private val stt by sttDelegate
    private val tts by ttsDelegate
    private val sherpa by lazy { SherpaEngineFactory(logger) }
    private val sink by lazy { AndroidSpeaker() }

    override fun osStt(): SttEngine = stt
    override fun osTts(): TtsEngine = tts
    override fun onDevice(): OnDeviceEngineFactory = sherpa
    override fun audioSource(sampleRate: Int): AudioSource {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw VoiceException(ErrorCode.PERMISSION_DENIED, "RECORD_AUDIO permission not granted")
        }
        return AndroidMicrophone(sampleRate)
    }
    override fun audioSink(): AudioSink = sink
    override fun close() {
        if (sttDelegate.isInitialized()) stt.close()
        if (ttsDelegate.isInitialized()) tts.close()
        sink.stop()
    }

    override fun deviceInfo(): Map<String, Any?> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return linkedMapOf(
            "platform" to "android", "model" to "${Build.MANUFACTURER} ${Build.MODEL}", "sdk" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE,
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: ""), "cpus" to Runtime.getRuntime().availableProcessors(),
            "ramMb" to mi.totalMem / 1048576, "availRamMb" to mi.availMem / 1048576, "lowRamDevice" to am.isLowRamDevice,
        )
    }
}

class AndroidLogger(level: String) : Logger {
    private val lvl = when (level.lowercase()) { "debug" -> 0; "info" -> 1; "warn" -> 2; else -> 3 }
    override fun debug(msg: String) { if (lvl <= 0) Log.d("IndicVoice", msg) }
    override fun info(msg: String) { if (lvl <= 1) Log.i("IndicVoice", msg) }
    override fun warn(msg: String) { if (lvl <= 2) Log.w("IndicVoice", msg) }
    override fun error(msg: String, t: Throwable?) { Log.e("IndicVoice", msg, t) }
}

/** 16-bit mono capture with AudioRecord (VOICE_RECOGNITION source = OS noise suppression/AGC where available). */
class AndroidMicrophone(override val sampleRate: Int) : AudioSource {
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(onFrame: (ShortArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate / 2 * 2))
        } catch (e: SecurityException) { throw VoiceException(ErrorCode.PERMISSION_DENIED, "RECORD_AUDIO permission not granted") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw VoiceException(ErrorCode.NO_ENGINE, "AudioRecord failed to initialise") }
        record = rec
        try { rec.startRecording() } catch (e: Exception) { rec.release(); record = null; throw VoiceException(ErrorCode.NO_ENGINE, "AudioRecord.startRecording failed: ${e.message}", cause = e) }
        running = true
        thread = Thread({
            val frame = ShortArray(sampleRate / 50) // 20 ms
            while (running) {
                val n = rec.read(frame, 0, frame.size)
                if (n > 0) onFrame(if (n == frame.size) frame.copyOf() else frame.copyOf(n))
            }
        }, "indicvoice-mic").apply { start() }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        record?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        record = null
    }
}

class AndroidSpeaker : AudioSink {
    @Volatile private var track: AudioTrack? = null

    override fun play(audio: PcmAudio, onDone: (() -> Unit)?) {
        stop()
        Thread({
            val bytes = audio.toByteArrayLE()
            var t: AudioTrack? = null
            try {
                t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = t
                t.play()
                var off = 0
                while (off < bytes.size && track === t) {
                    val n = t.write(bytes, off, minOf(8192, bytes.size - off))
                    if (n <= 0) break
                    off += n
                }
                if (track === t) { // wait for the buffer to drain
                    val frames = bytes.size / 2
                    var waited = 0
                    while (track === t && t.playbackHeadPosition < frames && waited < 10000) { Thread.sleep(20); waited += 20 }
                }
            } catch (e: Exception) { Log.w("IndicVoice", "playback failed: $e") }
            finally { t?.let { tr -> try { tr.stop(); tr.release() } catch (_: Exception) {}; if (track === tr) track = null }; onDone?.invoke() }
        }, "indicvoice-play").start()
    }

    override fun stop() { track?.let { try { it.pause(); it.flush() } catch (_: Exception) {} }; track = null }
}

/** Convenience entry point for Android apps and the React Native module. */
object IndicVoiceAndroid {
    /**
     * Loads voice-config.json (and catalog.json) from assets, keeping a copy
     * under filesDir so it can be replaced at runtime (e.g. by a remote fetch).
     */
    fun create(context: Context, configAsset: String = "voice-config.json", catalogAsset: String = "catalog.json", configOverride: String? = null): IndicVoice {
        val dir = File(context.filesDir, "indicvoice").also { it.mkdirs() }
        val cfgFile = File(dir, "voice-config.json")
        val catFile = File(dir, "catalog.json")
        if (configOverride != null) cfgFile.writeText(configOverride)
        else if (!cfgFile.exists()) context.assets.open(configAsset).use { cfgFile.writeBytes(it.readBytes()) }
        try { context.assets.open(catalogAsset).use { catFile.writeBytes(it.readBytes()) } } catch (_: Exception) {}
        val config = VoiceConfig.load(cfgFile)
        return IndicVoice(config, AndroidPlatform(context, config))
    }
}
