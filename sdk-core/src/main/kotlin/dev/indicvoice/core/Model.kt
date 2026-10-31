package dev.indicvoice.core

/** Which of the three tiers produced a result. */
enum class Tier(val number: Int, val label: String) {
    OS(1, "tier1-os"),            // the device's own speech engine (Android/iOS/browser)
    ON_DEVICE(2, "tier2-on-device"), // our downloadable model packs running locally (sherpa-onnx)
    HOSTED(3, "tier3-hosted");    // the integrator's backend (proxy to Sarvam / local model / anything)

    companion object {
        fun of(n: Int): Tier? = entries.firstOrNull { it.number == n }
    }
}

enum class Feature { STT, TTS }

/** One recognised word. Timings are optional because OS engines rarely provide them. */
data class Word(
    val text: String,
    val startMs: Int? = null,
    val endMs: Int? = null,
    val confidence: Double? = null,
) : JsonSerializable {
    override fun toJsonValue(): Any? {
        val m = linkedMapOf<String, Any?>("text" to text)
        if (startMs != null) m["startMs"] = startMs
        if (endMs != null) m["endMs"] = endMs
        if (confidence != null) m["confidence"] = Math.round(confidence * 100) / 100.0
        return m
    }
}

/**
 * The STT output format. The language is always explicit, as requested:
 * `language` tells the caller what was spoken, `words` what was said.
 */
data class TranscriptResult(
    val text: String,
    val words: List<Word>,
    val language: DetectedLanguage,
    val tier: Tier,
    val engine: String,
    val latencyMs: Long,
    val audioMs: Long,
    val confidence: Double? = null,
    val isFinal: Boolean = true,
    val raw: Map<String, Any?> = emptyMap(),
) : JsonSerializable {
    /** Real-time factor: processing time / audio duration (lower is better). */
    val rtf: Double get() = if (audioMs <= 0) 0.0 else latencyMs.toDouble() / audioMs

    override fun toJsonValue(): Any? = linkedMapOf(
        "language" to language.toJsonValue(),
        "text" to text,
        "words" to words.map { it.toJsonValue() },
        "wordCount" to words.size,
        "confidence" to confidence?.let { Math.round(it * 100) / 100.0 },
        "tier" to tier.number,
        "tierLabel" to tier.label,
        "engine" to engine,
        "latencyMs" to latencyMs,
        "audioMs" to audioMs,
        "rtf" to Math.round(rtf * 1000) / 1000.0,
        "isFinal" to isFinal,
    )

    companion object {
        /** Builds a result from plain text: splits words, detects the language. */
        fun fromText(
            text: String,
            tier: Tier,
            engine: String,
            latencyMs: Long,
            audioMs: Long,
            engineLang: String? = null,
            hint: Language? = null,
            allowed: Collection<Language> = emptyList(),
            words: List<Word>? = null,
            confidence: Double? = null,
            isFinal: Boolean = true,
            raw: Map<String, Any?> = emptyMap(),
        ): TranscriptResult {
            val cleaned = text.trim()
            val detected = LanguageDetector.detect(cleaned, engineLang, hint, allowed)
            val ws = words ?: splitWords(cleaned)
            return TranscriptResult(cleaned, ws, detected, tier, engine, latencyMs, audioMs, confidence, isFinal, raw)
        }

        fun splitWords(text: String): List<Word> =
            text.split(Regex("\\s+")).map { it.trim().trim('।', '॥', ',', '.', '!', '?', ';', ':', '"', '\'') }
                .filter { it.isNotEmpty() }.map { Word(it) }

        @Suppress("UNCHECKED_CAST")
        fun fromJson(m: Map<String, Any?>, tierOverride: Tier? = null, latencyOverride: Long? = null): TranscriptResult {
            val langObj = m.obj("language")
            val langCode = langObj?.str("code") ?: m.str("languageCode") ?: m.str("language_code")
            val text = m.str("text") ?: m.str("transcript") ?: ""
            val tier = tierOverride ?: Tier.of(m.int("tier", 3)) ?: Tier.HOSTED
            val words = m.objList("words").map { w ->
                Word(w.str("text") ?: w.str("word") ?: "", (w["startMs"] as? Number)?.toInt(), (w["endMs"] as? Number)?.toInt(), (w["confidence"] as? Number)?.toDouble())
            }.filter { it.text.isNotEmpty() }
            val lang = Language.parse(langCode)
            val detected = if (lang != null && langObj != null) {
                DetectedLanguage(lang, Script.entries.firstOrNull { it.iso15924 == langObj.str("script") } ?: lang.script,
                    langObj.double("confidence", 0.8), langObj.str("source") ?: "engine")
            } else LanguageDetector.detect(text, langCode)
            return TranscriptResult(
                text = text,
                words = if (words.isEmpty()) splitWords(text) else words,
                language = detected,
                tier = tier,
                engine = m.str("engine") ?: "backend",
                latencyMs = latencyOverride ?: m.long("latencyMs", 0),
                audioMs = m.long("audioMs", 0),
                confidence = (m["confidence"] as? Number)?.toDouble(),
                isFinal = m.bool("isFinal", true),
                raw = m,
            )
        }
    }
}

/** TTS output is deliberately simple: audio in the language that was asked for. */
data class TtsResult(
    val audio: PcmAudio,
    val language: Language,
    val voiceId: String,
    val tier: Tier,
    val engine: String,
    val latencyMs: Long,
) : JsonSerializable {
    override fun toJsonValue(): Any? = linkedMapOf(
        "language" to language.code,
        "voiceId" to voiceId,
        "tier" to tier.number,
        "tierLabel" to tier.label,
        "engine" to engine,
        "latencyMs" to latencyMs,
        "audioMs" to audio.durationMs,
        "sampleRate" to audio.sampleRate,
    )
}

data class VoiceInfo(
    val id: String,
    val language: Language,
    val tier: Tier,
    val name: String,
    val gender: String? = null,
    val offline: Boolean = true,
    val quality: String? = null,
    val installed: Boolean = true,
) : JsonSerializable {
    override fun toJsonValue(): Any? = linkedMapOf(
        "id" to id, "language" to language.code, "tier" to tier.number, "name" to name,
        "gender" to gender, "offline" to offline, "quality" to quality, "installed" to installed,
    )
}

/** Why an engine is (not) usable right now. */
data class Availability(
    val available: Boolean,
    val reason: String? = null,
    val details: Map<String, Any?> = emptyMap(),
) : JsonSerializable {
    override fun toJsonValue(): Any? = linkedMapOf("available" to available, "reason" to reason, "details" to details)

    companion object {
        val YES = Availability(true)
        fun no(reason: String, details: Map<String, Any?> = emptyMap()) = Availability(false, reason, details)
    }
}

class VoiceException(
    val code: ErrorCode,
    message: String,
    val tier: Tier? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun toJsonValue(): Any? = linkedMapOf("code" to code.name, "message" to message, "tier" to tier?.number)
}

enum class ErrorCode {
    NO_ENGINE, FEATURE_DISABLED, LANGUAGE_UNSUPPORTED, PERMISSION_DENIED, PACK_MISSING, PACK_CORRUPT,
    NETWORK, BACKEND_ERROR, TIMEOUT, ENGINE_FAILED, BAD_AUDIO, CONFIG_ERROR, CANCELLED
}

/** Options for one transcription call. */
data class SttOptions(
    /** Language hint. null = auto-detect. */
    val language: Language? = null,
    /** Force a tier (1/2/3). null = follow policy. */
    val tier: Tier? = null,
    /** Phrases to bias recognition towards (used by engines that support it). */
    val keyterms: List<String> = emptyList(),
    /** Restrict language detection to this set. Empty = any of the 23. */
    val allowedLanguages: List<Language> = emptyList(),
    /** Specific pack id for tier 2, else the config's choice for the language. */
    val packId: String? = null,
)

data class TtsOptions(
    val language: Language,
    val text: String,
    val tier: Tier? = null,
    val voiceId: String? = null,
    val speed: Double = 1.0,
    val sampleRate: Int? = null,
    val packId: String? = null,
)
