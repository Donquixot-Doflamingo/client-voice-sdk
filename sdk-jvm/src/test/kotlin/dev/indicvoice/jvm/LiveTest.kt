package dev.indicvoice.jvm

import dev.indicvoice.core.*

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live-path test: replays a recording as 20 ms microphone frames (silence + utterance + silence, twice)
 * through IndicVoice.startLive → VAD segmenter → tier-2 engine, and checks two finals + a clean stop.
 * Run: scripts/run-tests.sh (section 11) or see the kotlinc line there.
 */
/** Replays a WAV as 20 ms frames (with leading/trailing silence) to exercise the live path end to end. */
class ReplaySource(private val wav: PcmAudio) : AudioSource {
    override val sampleRate = 16000
    @Volatile private var running = false
    override fun start(onFrame: (ShortArray) -> Unit) {
        running = true
        Thread({
            val pcm = PcmAudio.concat(listOf(PcmAudio.silence(400), wav, PcmAudio.silence(1500), wav, PcmAudio.silence(1500)), 16000)
            var i = 0
            while (running && i + 320 <= pcm.samples.size) { onFrame(pcm.samples.copyOfRange(i, i + 320)); i += 320; Thread.sleep(20) }
        }, "replay").apply { isDaemon = true; start() }
    }
    override fun stop() { running = false }
}

fun main(args: Array<String>) {
    val wav = Wav.read(File(args[0])).resampled(16000)
    val cfg = VoiceConfig.load(File("config/voice-config.json"))
    val base = JvmPlatform(File(System.getProperty("user.home"), ".indicvoice/packs"), Logger.stdout("warn"))
    val platform = object : Platform by base { override fun audioSource(sampleRate: Int): AudioSource = ReplaySource(wav) }
    val voice = IndicVoice(cfg, platform)
    val finals = ArrayList<TranscriptResult>()
    val events = ArrayList<String>()
    val stopped = CountDownLatch(1)
    val session = voice.startLive(SttOptions(language = Language.HINDI), object : LiveSttListener {
        override fun onReady() { events += "ready" }
        override fun onSpeechStart() { events += "speechStart" }
        override fun onSpeechEnd() { events += "speechEnd" }
        override fun onFinal(result: TranscriptResult) { events += "final"; finals += result; println("FINAL [${result.language.language.code}] ${result.text}  (${result.latencyMs} ms, tier ${result.tier.number}, ${result.engine})") }
        override fun onTierChanged(tier: Tier, reason: String) { events += "tier:${tier.number}" }
        override fun onError(error: VoiceException) { events += "error:${error.code}"; println("ERROR ${error.code} ${error.message}") }
        override fun onStopped() { events += "stopped"; stopped.countDown() }
    })
    println("session tier=${session.tier.number}")
    Thread.sleep((wav.durationMs * 2 + 5000))
    session.stop()
    val ok = stopped.await(15, TimeUnit.SECONDS)
    println("events: " + events.joinToString(" "))
    println("finals=${finals.size} stoppedInTime=$ok lastEventIsStopped=${events.lastOrNull() == "stopped"}")
    voice.close()
    if (finals.size != 2 || !ok || events.last() != "stopped") { println("LIVE TEST FAILED"); System.exit(1) } else println("LIVE TEST PASSED")
}
