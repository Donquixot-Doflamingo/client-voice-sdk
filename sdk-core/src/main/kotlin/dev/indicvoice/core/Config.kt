package dev.indicvoice.core

import java.io.File

/**
 * Typed view of `voice-config.json`. Everything here can be changed without a
 * code change: ship a new JSON with the app, or host it and let the app fetch it.
 *
 * Secrets never live here. The only runtime inputs that come from outside the
 * file are the backend URL/token, which the host app injects through
 * [IndicVoice.setBackend] (the test app reads them from its own .env or UI).
 */
data class VoiceConfig(
    val version: Int = 1,
    val features: Set<Feature> = setOf(Feature.STT, Feature.TTS),
    val defaultLanguage: Language = Language.HINDI,
    val allowedLanguages: List<Language> = Language.entries.toList(),
    val stt: SttConfig = SttConfig(),
    val tts: TtsConfig = TtsConfig(),
    val packs: PacksConfig = PacksConfig(),
    val logLevel: String = "info",
    val redactTranscripts: Boolean = true,
    /** The raw map, so platform layers can read keys this class does not model. */
    val raw: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun fromJson(text: String, baseDir: File? = null): VoiceConfig = fromMap(Json.parseObject(text), baseDir)

        fun load(file: File): VoiceConfig = fromJson(file.readText(), file.absoluteFile.parentFile)

        fun fromMap(m: Map<String, Any?>, baseDir: File? = null): VoiceConfig {
            val features = m.obj("features")
            val feats = HashSet<Feature>()
            if (features == null || features.bool("stt", true)) feats += Feature.STT
            if (features == null || features.bool("tts", true)) feats += Feature.TTS
            val allowed = m.strList("allowedLanguages").mapNotNull { Language.parse(it) }.ifEmpty { Language.entries.toList() }
            val packsObj = m.obj("packs") ?: emptyMap()
            var catalogInline: List<PackSpec> = emptyList()
            val inline = packsObj["catalogInline"]
            when (inline) {
                is String -> {
                    val f = if (baseDir != null) File(baseDir, inline) else File(inline)
                    if (f.exists()) catalogInline = PackCatalog.parse(f.readText())
                }
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") run { catalogInline = PackCatalog.parseMap(inline as Map<String, Any?>) }
                is List<*> -> catalogInline = PackCatalog.parseMap(mapOf("packs" to inline))
            }
            return VoiceConfig(
                version = m.int("version", 1),
                features = feats,
                defaultLanguage = Language.parse(m.str("defaultLanguage")) ?: Language.HINDI,
                allowedLanguages = allowed,
                stt = SttConfig.fromMap(m.obj("stt") ?: emptyMap()),
                tts = TtsConfig.fromMap(m.obj("tts") ?: emptyMap()),
                packs = PacksConfig(
                    catalogUrl = packsObj.str("catalogUrl") ?: "",
                    catalogRefreshHours = packsObj.int("catalogRefreshHours", 24),
                    storageDir = packsObj.str("storageDir") ?: "",
                    autoDownloadOnWifi = packsObj.bool("autoDownloadOnWifi", false),
                    catalogInline = catalogInline,
                ),
                logLevel = m.obj("logging")?.str("level") ?: "info",
                redactTranscripts = m.obj("logging")?.bool("redactTranscripts", true) ?: true,
                raw = m,
            )
        }
    }
}

enum class TierPolicy { DEVICE_FIRST, DEVICE_ONLY, HOSTED_FIRST, HOSTED_ONLY, ORDER;
    companion object { fun parse(s: String?): TierPolicy = entries.firstOrNull { it.name.equals(s, true) } ?: DEVICE_FIRST }
}

enum class VoicePolicy { OS_FIRST, CONSISTENT, DEVICE_ONLY, HOSTED_FIRST, HOSTED_ONLY, ORDER;
    companion object { fun parse(s: String?): VoicePolicy = entries.firstOrNull { it.name.equals(s, true) } ?: OS_FIRST }
}

data class HostedConfig(
    val enabled: Boolean = true,
    val backendUrl: String = "",
    val path: String = "/v1/stt",
    val timeoutMs: Int = 20000,
    val authHeader: String = "Authorization",
    val authValuePrefix: String = "Bearer ",
    val sendFormat: String = "wav",
) {
    companion object {
        fun fromMap(m: Map<String, Any?>, pathKey: String, defaultPath: String) = HostedConfig(
            enabled = m.bool("enabled", true),
            backendUrl = m.str("backendUrl") ?: "",
            path = m.str(pathKey) ?: defaultPath,
            timeoutMs = m.int("timeoutMs", 20000),
            authHeader = m.str("authHeader") ?: "Authorization",
            authValuePrefix = m.str("authValuePrefix") ?: "Bearer ",
            sendFormat = m.str("sendFormat") ?: "wav",
        )
    }
}

data class LiveConfig(
    val endpointSilenceMs: Int = 700,
    val maxUtteranceMs: Int = 15000,
    val vadThreshold: Double = 0.5,
    val minSpeechMs: Int = 250,
)

data class SttConfig(
    val tierPolicy: TierPolicy = TierPolicy.DEVICE_FIRST,
    val tierOrder: List<Tier> = listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED),
    val fallbackOnError: Boolean = true,
    val fallbackOnLowConfidence: Double = 0.0,
    val languageDetection: Boolean = true,
    val live: LiveConfig = LiveConfig(),
    val tier1Enabled: Boolean = true,
    val tier1PreferOnDevice: Boolean = true,
    val tier1AllowOnline: Boolean = true,
    val tier2Enabled: Boolean = true,
    val tier2NumThreads: Int = 2,
    val tier2VadPack: String = "silero-vad",
    val tier2DefaultPack: String = "",
    val tier2PackByLanguage: Map<String, String> = emptyMap(),
    val tier2MaxLoadedPacks: Int = 2,
    val tier3: HostedConfig = HostedConfig(path = "/v1/stt"),
    val languageId: LanguageIdConfig = LanguageIdConfig(),
) {
    fun packFor(language: Language?): String? {
        val key = language?.code ?: "auto"
        return tier2PackByLanguage[key] ?: tier2PackByLanguage["*"] ?: tier2DefaultPack.ifBlank { null }
    }

    companion object {
        fun fromMap(m: Map<String, Any?>): SttConfig {
            val t1 = m.obj("tier1") ?: emptyMap(); val t2 = m.obj("tier2") ?: emptyMap(); val live = m.obj("live") ?: emptyMap()
            return SttConfig(
                tierPolicy = TierPolicy.parse(m.str("tierPolicy")),
                tierOrder = m.list("tierOrder")?.mapNotNull { (it as? Number)?.toInt()?.let(Tier::of) }?.ifEmpty { null } ?: listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED),
                fallbackOnError = m.bool("fallbackOnError", true),
                fallbackOnLowConfidence = m.double("fallbackOnLowConfidence", 0.0),
                languageDetection = m.obj("languageDetection")?.bool("enabled", true) ?: true,
                live = LiveConfig(live.int("endpointSilenceMs", 700), live.int("maxUtteranceMs", 15000), live.double("vadThreshold", 0.5), live.int("minSpeechMs", 250)),
                tier1Enabled = t1.bool("enabled", true),
                tier1PreferOnDevice = t1.bool("preferOnDevice", true),
                tier1AllowOnline = t1.bool("allowOnlineOsEngine", true),
                tier2Enabled = t2.bool("enabled", true),
                tier2NumThreads = t2.int("numThreads", 2),
                tier2VadPack = t2.str("vadPack") ?: "silero-vad",
                tier2DefaultPack = t2.str("defaultPack") ?: "",
                tier2PackByLanguage = t2.strMap("packByLanguage"),
                tier2MaxLoadedPacks = t2.int("maxLoadedPacks", 2).coerceAtLeast(1),
                tier3 = HostedConfig.fromMap(m.obj("tier3") ?: emptyMap(), "sttPath", "/v1/stt"),
                languageId = LanguageIdConfig.fromMap(m.obj("languageId") ?: emptyMap()),
            )
        }
    }
}

/**
 * Auto mode (no language given): identify the spoken language from the audio with a
 * multilingual Whisper pack, then transcribe with that language's pack.
 *  - routeAs: transcribe a detected language with another language's pack (e.g. "ur" → the Hindi pack,
 *    which writes Urdu or Hindi script depending on what was said).
 *  - fallbackLanguages: tried when the language is not allowed / not identified, or when a pack's output is
 *    implausible (written in a script none of our languages use).
 */
data class LanguageIdConfig(
    val enabled: Boolean = true,
    val pack: String = "whisper-base-int8",
    val routeAs: Map<String, String> = mapOf("ur" to "hi"),
    val fallbackLanguages: List<String> = listOf("en", "hi"),
    val minAudioMs: Int = 250,
) {
    companion object {
        fun fromMap(m: Map<String, Any?>) = LanguageIdConfig(
            enabled = m.bool("enabled", true),
            pack = m.str("pack") ?: "whisper-base-int8",
            routeAs = if (m.containsKey("routeAs")) m.strMap("routeAs") else mapOf("ur" to "hi"),
            fallbackLanguages = m.strList("fallbackLanguages").ifEmpty { listOf("en", "hi") },
            minAudioMs = m.int("minAudioMs", 250),
        )
    }
}

data class TtsConfig(
    val voicePolicy: VoicePolicy = VoicePolicy.OS_FIRST,
    val tierOrder: List<Tier> = listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED),
    val fallbackOnError: Boolean = true,
    val sampleRate: Int = 22050,
    val tier1Enabled: Boolean = true,
    val tier1PreferOffline: Boolean = true,
    val tier2Enabled: Boolean = true,
    val tier2NumThreads: Int = 2,
    val tier2PackByLanguage: Map<String, String> = emptyMap(),
    val tier3: HostedConfig = HostedConfig(path = "/v1/tts"),
    val preferredVoices: Map<String, String> = emptyMap(),
) {
    fun packFor(language: Language): String? = tier2PackByLanguage[language.code] ?: tier2PackByLanguage["*"]

    companion object {
        fun fromMap(m: Map<String, Any?>): TtsConfig {
            val t1 = m.obj("tier1") ?: emptyMap(); val t2 = m.obj("tier2") ?: emptyMap()
            return TtsConfig(
                voicePolicy = VoicePolicy.parse(m.str("voicePolicy")),
                tierOrder = m.list("tierOrder")?.mapNotNull { (it as? Number)?.toInt()?.let(Tier::of) }?.ifEmpty { null } ?: listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED),
                fallbackOnError = m.bool("fallbackOnError", true),
                sampleRate = m.int("sampleRate", 22050),
                tier1Enabled = t1.bool("enabled", true),
                tier1PreferOffline = t1.bool("preferOffline", true),
                tier2Enabled = t2.bool("enabled", true),
                tier2NumThreads = t2.int("numThreads", 2),
                tier2PackByLanguage = t2.strMap("packByLanguage"),
                tier3 = HostedConfig.fromMap(m.obj("tier3") ?: emptyMap(), "ttsPath", "/v1/tts"),
                preferredVoices = m.strMap("preferredVoices").filterValues { it.isNotBlank() },
            )
        }
    }
}

data class PacksConfig(
    val catalogUrl: String = "",
    val catalogRefreshHours: Int = 24,
    val storageDir: String = "",
    val autoDownloadOnWifi: Boolean = false,
    val catalogInline: List<PackSpec> = emptyList(),
)

/** One entry of catalog.json. */
data class PackSpec(
    val id: String,
    val kind: String,                 // stt | tts | vad
    val engine: String,               // dolphin-ctc | omnilingual-ctc | whisper | nemo-ctc | zipformer-ctc | sense-voice | vits | kokoro | matcha | silero-vad
    val name: String,
    val languages: List<Language>,
    val sizeBytes: Long,
    val license: String,
    val commercialUse: Boolean,
    val archive: ArchiveSpec?,
    val files: List<FileSpec>,
    val model: Map<String, Any?>,     // engine-specific file names relative to the pack dir
    val notes: String,
    val version: String,
    val minRamMb: Int,
    val raw: Map<String, Any?>,
) : JsonSerializable {
    val languageCodes: List<String> get() = languages.map { it.code }
    fun supports(language: Language?): Boolean = language == null || languages.isEmpty() || language in languages
    override fun toJsonValue(): Any? = linkedMapOf(
        "id" to id, "kind" to kind, "engine" to engine, "name" to name, "languages" to languageCodes,
        "sizeBytes" to sizeBytes, "sizeMb" to Math.round(sizeBytes / 1048576.0), "license" to license, "commercialUse" to commercialUse,
        "version" to version, "notes" to notes,
    )
}

data class ArchiveSpec(val url: String, val format: String, val sha256: String?, val stripTopDir: Boolean)
data class FileSpec(val path: String, val url: String, val sha256: String?, val sizeBytes: Long)

object PackCatalog {
    fun parse(text: String): List<PackSpec> = parseMap(Json.parseObject(text))

    fun parseMap(m: Map<String, Any?>): List<PackSpec> = m.objList("packs").map { p ->
        val arch = p.obj("archive")?.let { a ->
            ArchiveSpec(a.str("url") ?: "", a.str("format") ?: guessFormat(a.str("url") ?: ""), a.str("sha256"), a.bool("stripTopDir", true))
        }
        PackSpec(
            id = p.str("id") ?: throw VoiceException(ErrorCode.CONFIG_ERROR, "pack without id"),
            kind = p.str("kind") ?: "stt",
            engine = p.str("engine") ?: "",
            name = p.str("name") ?: p.str("id")!!,
            languages = p.strList("languages").mapNotNull { Language.parse(it) },
            sizeBytes = p.long("sizeBytes", 0),
            license = p.str("license") ?: "",
            commercialUse = p.bool("commercialUse", true),
            archive = arch,
            files = p.objList("files").map { f -> FileSpec(f.str("path") ?: "", f.str("url") ?: "", f.str("sha256"), f.long("sizeBytes", 0)) },
            model = p.obj("model") ?: emptyMap(),
            notes = p.str("notes") ?: "",
            version = p.str("version") ?: "1",
            minRamMb = p.int("minRamMb", 0),
            raw = p,
        )
    }

    fun guessFormat(url: String): String = when {
        url.endsWith(".tar.bz2") || url.endsWith(".tbz2") -> "tar.bz2"
        url.endsWith(".tar.gz") || url.endsWith(".tgz") -> "tar.gz"
        url.endsWith(".zip") -> "zip"
        url.endsWith(".tar") -> "tar"
        else -> "file"
    }
}
