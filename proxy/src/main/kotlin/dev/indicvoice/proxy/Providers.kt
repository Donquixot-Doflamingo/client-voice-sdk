package dev.indicvoice.proxy

import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.Http
import dev.indicvoice.core.IndicVoice
import dev.indicvoice.core.Json
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.SttOptions
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TranscriptResult
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.TtsResult
import dev.indicvoice.core.VoiceConfig
import dev.indicvoice.core.VoiceException
import dev.indicvoice.core.Wav
import dev.indicvoice.core.Word
import dev.indicvoice.core.obj
import dev.indicvoice.core.objList
import dev.indicvoice.core.str
import dev.indicvoice.core.strMap
import dev.indicvoice.jvm.JvmPlatform
import java.util.Base64

/**
 * Free provider: runs the SDK's own tier-2 engines (sherpa-onnx packs) on the
 * server. Configure which packs in proxy/config.json → "local".
 */
class LocalProvider(cfg: ProxyConfig, private val log: Logger) : SttProvider, TtsProvider {
    override val id = "local"
    private val voice: IndicVoice
    private val sttPackByLanguage: Map<String, String>
    private val ttsPackByLanguage: Map<String, String>

    init {
        val base = cfg.sdkConfigFile?.let { VoiceConfig.load(it) } ?: VoiceConfig()
        val sttOverride = cfg.local.strMap("sttPackByLanguage")
        val ttsOverride = cfg.local.strMap("ttsPackByLanguage")
        sttPackByLanguage = base.stt.tier2PackByLanguage + sttOverride
        ttsPackByLanguage = base.tts.tier2PackByLanguage + ttsOverride
        val platform = JvmPlatform(cfg.packsDir, log)
        voice = IndicVoice(base.copy(stt = base.stt.copy(tier2NumThreads = (cfg.local["numThreads"] as? Number)?.toInt() ?: base.stt.tier2NumThreads, tier2PackByLanguage = sttPackByLanguage,
                tier2MaxLoadedPacks = (cfg.local["maxLoadedPacks"] as? Number)?.toInt() ?: 4),
            tts = base.tts.copy(tier2PackByLanguage = ttsPackByLanguage)), platform)
        // Warm up: load the default packs in the background so the first request is not paying model-load time.
        if ((cfg.local["preload"] as? Boolean) != false) Thread({
            try {
                val lang = base.defaultLanguage
                val t0 = System.currentTimeMillis()
                voice.sttEngine(Tier.ON_DEVICE, lang)
                voice.ttsEngine(Tier.ON_DEVICE, lang)
                voice.languageIdentifier()
                log.info("local provider warmed up for ${lang.code} in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) { log.warn("warm-up failed: ${e.message}") }
        }, "indicvoice-warmup").apply { isDaemon = true; start() }
    }

    override fun available(): Boolean = true

    override fun supportsStt(language: Language?): Boolean {
        val pack = voice.config.stt.packFor(language) ?: return false
        return voice.packs.isInstalled(pack) || voice.packs.catalog().any { it.kind == "stt" && it.supports(language) && voice.packs.isInstalled(it.id) }
    }

    fun identifyLanguage(audio: PcmAudio): Map<String, Any?> = voice.identifyLanguage(audio)

    /** Installed STT packs that know [language] (for the accuracy test). */
    fun installedSttPacks(language: Language): List<String> =
        voice.packs.catalog().filter { it.kind == "stt" && it.supports(language) && voice.packs.isInstalled(it.id) }.map { it.id }

    /** One transcription with an explicit pack (or Auto mode when both are null). */
    fun transcribeWith(audio: PcmAudio, language: Language?, packId: String?): TranscriptResult =
        voice.transcribe(audio, SttOptions(language = language, tier = Tier.ON_DEVICE, packId = packId))

    override fun transcribe(audio: PcmAudio, language: Language?, keyterms: List<String>): TranscriptResult {
        val r = voice.transcribe(audio, SttOptions(language = language, tier = Tier.ON_DEVICE, keyterms = keyterms))
        return r.copy(engine = "local/" + r.engine.removePrefix("sherpa:"))
    }

    override fun supportsTts(language: Language): Boolean {
        val (engine, av) = voice.ttsEngine(Tier.ON_DEVICE, language)
        if (engine != null && av.available) return true
        val (os, osAv) = voice.ttsEngine(Tier.OS, language)
        return os != null && osAv.available
    }

    override fun voices(language: Language?): List<Map<String, Any?>> =
        voice.voices(language).filter { it.installed }.map { v -> linkedMapOf("id" to v.id, "language" to v.language.code, "name" to v.name, "gender" to v.gender, "quality" to v.quality) }

    override fun synthesize(text: String, language: Language, voiceId: String?, speed: Double, sampleRate: Int): TtsResult {
        val r = try {
            voice.synthesize(TtsOptions(language = language, text = text, tier = if (voiceId == null) Tier.ON_DEVICE else null, voiceId = voiceId, speed = speed, sampleRate = sampleRate))
        } catch (e: VoiceException) {
            // Fall back to the OS voice (macOS `say`) if no pack covers the language.
            voice.synthesize(TtsOptions(language = language, text = text, tier = Tier.OS, speed = speed, sampleRate = sampleRate))
        }
        return r.copy(engine = "local/" + r.engine.removePrefix("sherpa:"), voiceId = "local:" + r.voiceId)
    }
}

/**
 * Sarvam AI provider (Saaras STT, Bulbul TTS). Needs SARVAM_API_KEY.
 * Vendor-specific formats stay here; the SDK only ever sees the neutral contract.
 */
class SarvamProvider(private val apiKey: String, private val cfg: Map<String, Any?>, private val log: Logger) : SttProvider, TtsProvider {
    override val id = "sarvam"
    private val base = cfg.str("baseUrl") ?: "https://api.sarvam.ai"
    private val sttModel = cfg.str("sttModel") ?: "saaras:v4"
    private val ttsModel = cfg.str("ttsModel") ?: "bulbul:v3"
    private val defaultSpeaker = cfg.str("ttsSpeaker") ?: ""
    private val timeoutMs = (cfg["timeoutMs"] as? Number)?.toInt() ?: 20000

    // Sarvam's language codes are BCP-47 with a couple of quirks (od-IN for Odia).
    private fun sarvamCode(l: Language): String = when (l) { Language.ODIA -> "od-IN"; else -> l.bcp47 }
    private val sttLanguages = setOf(Language.HINDI, Language.BENGALI, Language.KANNADA, Language.MALAYALAM, Language.MARATHI, Language.ODIA, Language.PUNJABI, Language.TAMIL, Language.TELUGU, Language.ENGLISH, Language.GUJARATI,
        Language.ASSAMESE, Language.URDU, Language.NEPALI, Language.KONKANI, Language.KASHMIRI, Language.SINDHI, Language.SANSKRIT, Language.SANTALI, Language.MANIPURI, Language.BODO, Language.MAITHILI, Language.DOGRI)
    private val ttsLanguages = setOf(Language.HINDI, Language.BENGALI, Language.KANNADA, Language.MALAYALAM, Language.MARATHI, Language.ODIA, Language.PUNJABI, Language.TAMIL, Language.TELUGU, Language.ENGLISH, Language.GUJARATI)

    override fun available(): Boolean = apiKey.isNotBlank()
    override fun supportsStt(language: Language?): Boolean = language == null || language in sttLanguages
    override fun supportsTts(language: Language): Boolean = language in ttsLanguages

    override fun transcribe(audio: PcmAudio, language: Language?, keyterms: List<String>): TranscriptResult {
        val mp = Http.Multipart()
            .field("model", sttModel)
            .field("language_code", language?.let { sarvamCode(it) } ?: "unknown")
            .field("with_timestamps", "true")
        if (keyterms.isNotEmpty() && sttModel.startsWith("saaras:v4")) mp.field("keyterms", Json.stringify(keyterms.take(50)))
        mp.file("file", "audio.wav", "audio/wav", audio.toWavBytes())
        val t0 = System.currentTimeMillis()
        val r = Http.request("POST", "$base/speech-to-text", mp.bytes(), mp.contentType, mapOf("api-subscription-key" to apiKey), timeoutMs)
        val latency = System.currentTimeMillis() - t0
        if (!r.ok) throw VoiceException(ErrorCode.BACKEND_ERROR, "sarvam HTTP ${r.status}: ${r.text.take(300)}")
        val m = Json.parseObject(r.text)
        val text = m.str("transcript") ?: ""
        val words = m.obj("timestamps")?.let { ts ->
            val w = (ts["words"] as? List<*>)?.map { it.toString() } ?: emptyList()
            val s = (ts["start_time_seconds"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: emptyList()
            val e = (ts["end_time_seconds"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: emptyList()
            if (w.isNotEmpty() && w.size == s.size && w.size == e.size) w.indices.map { i -> Word(w[i], (s[i] * 1000).toInt(), (e[i] * 1000).toInt()) } else null
        }
        return TranscriptResult.fromText(
            text = text, tier = Tier.HOSTED, engine = "sarvam/$sttModel", latencyMs = latency, audioMs = audio.durationMs,
            engineLang = m.str("language_code"), hint = language, words = words?.flatMap { seg -> TranscriptResult.splitWords(seg.text).map { Word(it.text, seg.startMs, seg.endMs) } },
            confidence = (m["language_probability"] as? Number)?.toDouble(), raw = mapOf("request_id" to m.str("request_id")),
        )
    }

    override fun voices(language: Language?): List<Map<String, Any?>> {
        val names = when {
            ttsModel.startsWith("bulbul:v2") -> listOf("anushka" to "female", "manisha" to "female", "vidya" to "female", "arya" to "female", "abhilash" to "male", "karun" to "male", "hitesh" to "male")
            ttsModel.startsWith("bulbul:v4") -> listOf("shubh_enhi_ads" to "male")
            else -> listOf("shubh" to "male", "aditya" to "male", "ritu" to "female", "priya" to "female", "neha" to "female", "rahul" to "male", "pooja" to "female", "rohan" to "male", "simran" to "female", "kavya" to "female", "amit" to "male", "shreya" to "female")
        }
        val langs = if (language != null) listOf(language).filter { it in ttsLanguages } else ttsLanguages.toList()
        return langs.flatMap { l -> names.map { (n, g) -> linkedMapOf<String, Any?>("id" to n, "language" to l.code, "name" to "Sarvam $n ($ttsModel)", "gender" to g, "quality" to "natural") } }
    }

    override fun synthesize(text: String, language: Language, voiceId: String?, speed: Double, sampleRate: Int): TtsResult {
        val body = LinkedHashMap<String, Any?>(mapOf(
            "text" to text, "target_language_code" to sarvamCode(language), "language_code" to sarvamCode(language),
            "model" to ttsModel, "pace" to speed.coerceIn(0.5, 2.0), "speech_sample_rate" to sampleRate, "output_audio_codec" to "wav",
        ))
        val speaker = voiceId?.takeIf { it.isNotBlank() } ?: defaultSpeaker.takeIf { it.isNotBlank() }
        if (speaker != null) body["speaker"] = speaker
        val t0 = System.currentTimeMillis()
        val r = Http.request("POST", "$base/text-to-speech", Json.stringify(body).toByteArray(Charsets.UTF_8), "application/json", mapOf("api-subscription-key" to apiKey), timeoutMs)
        val latency = System.currentTimeMillis() - t0
        if (!r.ok) throw VoiceException(ErrorCode.BACKEND_ERROR, "sarvam HTTP ${r.status}: ${r.text.take(300)}")
        val m = Json.parseObject(r.text)
        val audios = (m["audios"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        if (audios.isEmpty()) throw VoiceException(ErrorCode.BACKEND_ERROR, "sarvam returned no audio")
        val pcm = PcmAudio.concat(audios.map { Wav.decode(Base64.getDecoder().decode(it)) }, sampleRate)
        return TtsResult(pcm, language, "sarvam:${speaker ?: "default"}", Tier.HOSTED, "sarvam/$ttsModel", latency)
    }
}
