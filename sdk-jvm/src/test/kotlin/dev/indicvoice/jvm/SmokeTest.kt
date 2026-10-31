package dev.indicvoice.jvm

import dev.indicvoice.core.IndicVoice
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.Metrics
import dev.indicvoice.core.SttOptions
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.VoiceConfig
import java.io.File
import kotlin.system.exitProcess

/**
 * End-to-end smoke test used by scripts/setup.sh: downloads three small packs (once), speaks a Hindi
 * sentence with the on-device voice, transcribes it with the on-device recogniser and checks the result.
 * Proves on this machine: pack download + checksum + extraction, native runtime, TTS, STT, language detection.
 */
fun main(args: Array<String>) {
    val config = VoiceConfig.load(File(args.getOrElse(0) { "config/voice-config.json" }))
    val packsDir = System.getenv("INDICVOICE_PACKS_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".indicvoice/packs")
    val voice = IndicVoice(config, JvmPlatform(packsDir, Logger.stdout("warn")))
    try {
        for (id in listOf("silero-vad", "dolphin-base-int8", "piper-hi-priyamvada")) {
            if (voice.packs.isInstalled(id)) { println("   pack $id: installed"); continue }
            var last = -1L
            voice.packs.install(id) { _, phase, bytes, total ->
                val pct = if (total > 0) bytes * 100 / total else -1
                if (pct / 10 != last / 10) { print("\r   pack $id: $phase ${if (pct >= 0) "$pct%" else ""}     "); last = pct }
            }
            println("\r   pack $id: installed                    ")
        }
        val sentence = "नमस्ते, मेरा नाम मयंक है और मैं दिल्ली में रहता हूँ।"
        val tts = voice.synthesize(TtsOptions(language = Language.HINDI, text = sentence, voiceId = "pack:piper-hi-priyamvada"))
        println("   TTS: ${tts.audio.durationMs} ms of audio in ${tts.latencyMs} ms (${tts.engine})")
        val stt = voice.transcribe(tts.audio, SttOptions(language = Language.HINDI, tier = Tier.ON_DEVICE, packId = "dolphin-base-int8"))
        val wer = Metrics.wer(sentence, stt.text)
        println("   STT: \"${stt.text}\"  language=${stt.language.language.code}  ${stt.latencyMs} ms (${stt.engine})")
        println("   WER: ${"%.2f".format(wer)}")
        if (stt.language.language != Language.HINDI || wer > 0.35) { println("SMOKE TEST FAILED"); exitProcess(1) }
        println("SMOKE TEST PASSED")
    } finally { voice.close() }
}
