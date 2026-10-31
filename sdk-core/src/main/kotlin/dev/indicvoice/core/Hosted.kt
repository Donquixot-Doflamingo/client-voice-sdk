package dev.indicvoice.core

import java.util.Base64

/**
 * Runtime backend settings. The URL/token are injected by the host app (UI or
 * its own .env) and are deliberately not part of voice-config.json.
 */
class BackendSettings {
    @Volatile var url: String = ""
    @Volatile var token: String = ""
    @Volatile var extraHeaders: Map<String, String> = emptyMap()

    fun resolvedUrl(fromConfig: String): String = (url.ifBlank { fromConfig }).trimEnd('/')
}

/**
 * Tier 3 STT: talks to the integrator's backend using the SDK's own neutral
 * contract (see docs/BACKEND-CONTRACT.md). The backend decides which vendor to use;
 * the app never sees vendor keys or vendor-specific formats.
 *
 *   POST {backendUrl}{sttPath}
 *     Content-Type: audio/wav            (16 kHz mono 16-bit)
 *     X-Voice-Language: hi | auto
 *     X-Voice-Keyterms: UPI,Paytm        (optional)
 *     Authorization: Bearer <token>      (optional)
 *   -> 200 application/json  TranscriptResult
 */
class HostedSttEngine(
    private val cfg: HostedConfig,
    private val backend: BackendSettings,
    private val log: Logger = Logger.NONE,
) : SttEngine {
    override val id = "hosted-stt"
    override val tier = Tier.HOSTED
    @Volatile private var healthCache: Pair<Long, Map<String, Any?>>? = null

    private fun baseUrl() = backend.resolvedUrl(cfg.backendUrl)

    override fun availability(language: Language?): Availability {
        if (!cfg.enabled) return Availability.no("tier3 disabled in config")
        val url = baseUrl()
        if (url.isBlank()) return Availability.no("no backend URL configured (set it in the app / .env)")
        val health = health(url)
        return if (health == null) Availability(true, "backend URL set; health endpoint not reachable (will try anyway)", mapOf("url" to url))
        else Availability(true, null, mapOf("url" to url, "health" to health))
    }

    /** GET /v1/health, cached for a minute. Returns null when unreachable. */
    fun health(url: String = baseUrl(), force: Boolean = false): Map<String, Any?>? {
        val now = System.currentTimeMillis()
        val cached = healthCache
        if (!force && cached != null && now - cached.first < 60_000) return cached.second
        return try {
            val r = Http.request("GET", "$url/v1/health", headers = authHeaders(), timeoutMs = 4000)
            if (!r.ok) null else Json.parseObject(r.text).also { healthCache = now to it }
        } catch (e: Exception) { null }
    }

    private fun authHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>(backend.extraHeaders)
        if (backend.token.isNotBlank()) h[cfg.authHeader] = cfg.authValuePrefix + backend.token
        return h
    }

    override fun transcribe(audio: PcmAudio, options: SttOptions): TranscriptResult {
        val url = baseUrl()
        if (url.isBlank()) throw VoiceException(ErrorCode.NO_ENGINE, "No backend URL", tier)
        val pcm16k = audio.resampled(16000)
        val headers = LinkedHashMap(authHeaders())
        headers["X-Voice-Language"] = options.language?.code ?: LANGUAGE_AUTO
        if (options.keyterms.isNotEmpty()) headers["X-Voice-Keyterms"] = options.keyterms.joinToString(",")
        if (options.allowedLanguages.isNotEmpty()) headers["X-Voice-Allowed-Languages"] = options.allowedLanguages.joinToString(",") { it.code }
        val t0 = System.currentTimeMillis()
        val resp = try {
            if (cfg.sendFormat == "json") {
                val body = Json.stringify(mapOf(
                    "audioBase64" to Base64.getEncoder().encodeToString(pcm16k.toByteArrayLE()),
                    "encoding" to "pcm16", "sampleRate" to 16000,
                    "language" to (options.language?.code ?: LANGUAGE_AUTO), "keyterms" to options.keyterms,
                ))
                Http.request("POST", url + cfg.path, body.toByteArray(Charsets.UTF_8), "application/json", headers, cfg.timeoutMs)
            } else {
                Http.request("POST", url + cfg.path, pcm16k.toWavBytes(), "audio/wav", headers, cfg.timeoutMs)
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw VoiceException(ErrorCode.TIMEOUT, "Backend timed out after ${cfg.timeoutMs} ms", tier, e)
        } catch (e: java.io.IOException) {
            throw VoiceException(ErrorCode.NETWORK, "Backend unreachable: ${e.message}", tier, e)
        }
        val latency = System.currentTimeMillis() - t0
        if (!resp.ok) throw VoiceException(ErrorCode.BACKEND_ERROR, "Backend HTTP ${resp.status}: ${resp.text.take(300)}", tier)
        val m = try { Json.parseObject(resp.text) } catch (e: Exception) { throw VoiceException(ErrorCode.BACKEND_ERROR, "Backend returned non-JSON", tier, e) }
        val r = TranscriptResult.fromJson(m, Tier.HOSTED, latency)
        val detected = if (options.language != null || m.obj("language") == null) {
            // Re-run detection with our hint/allowed set so the output is consistent across tiers.
            LanguageDetector.detect(r.text, m.obj("language")?.str("code") ?: m.str("languageCode"), options.language, options.allowedLanguages)
        } else r.language
        return r.copy(language = detected, audioMs = pcm16k.durationMs, engine = "hosted:" + (m.str("engine") ?: m.str("provider") ?: "backend"))
    }
}

/**
 * Tier 3 TTS.
 *   POST {backendUrl}{ttsPath}  application/json
 *     { "text": "...", "language": "hi", "voiceId": "default", "speed": 1.0, "sampleRate": 22050, "format": "wav" }
 *   -> 200 audio/wav   (headers X-Voice-Id, X-Voice-Engine)
 *   or 200 application/json { "audioBase64": "...", "sampleRate": 22050, "voiceId": "...", "engine": "..." }
 */
class HostedTtsEngine(
    private val cfg: HostedConfig,
    private val backend: BackendSettings,
    private val log: Logger = Logger.NONE,
) : TtsEngine {
    override val id = "hosted-tts"
    override val tier = Tier.HOSTED
    private fun baseUrl() = backend.resolvedUrl(cfg.backendUrl)

    override fun availability(language: Language): Availability {
        if (!cfg.enabled) return Availability.no("tier3 disabled in config")
        val url = baseUrl()
        return if (url.isBlank()) Availability.no("no backend URL configured") else Availability(true, null, mapOf("url" to url))
    }

    override fun voices(language: Language?): List<VoiceInfo> {
        val url = baseUrl()
        if (url.isBlank()) return emptyList()
        return try {
            val r = Http.request("GET", "$url/v1/voices" + (language?.let { "?language=${it.code}" } ?: ""), headers = authHeaders(), timeoutMs = 5000)
            if (!r.ok) emptyList() else Json.parseObject(r.text).objList("voices").mapNotNull { v ->
                val lang = Language.parse(v.str("language")) ?: return@mapNotNull null
                VoiceInfo(v.str("id") ?: return@mapNotNull null, lang, Tier.HOSTED, v.str("name") ?: v.str("id")!!, v.str("gender"), false, v.str("quality"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun authHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>(backend.extraHeaders)
        if (backend.token.isNotBlank()) h[cfg.authHeader] = cfg.authValuePrefix + backend.token
        return h
    }

    override fun synthesize(options: TtsOptions): TtsResult {
        val url = baseUrl()
        if (url.isBlank()) throw VoiceException(ErrorCode.NO_ENGINE, "No backend URL", tier)
        val body = Json.stringify(mapOf(
            "text" to options.text, "language" to options.language.code, "voiceId" to (options.voiceId ?: "default"),
            "speed" to options.speed, "sampleRate" to (options.sampleRate ?: 22050), "format" to "wav",
        ))
        val t0 = System.currentTimeMillis()
        val resp = try {
            Http.request("POST", url + cfg.path, body.toByteArray(Charsets.UTF_8), "application/json", authHeaders(), cfg.timeoutMs)
        } catch (e: java.net.SocketTimeoutException) {
            throw VoiceException(ErrorCode.TIMEOUT, "Backend timed out", tier, e)
        } catch (e: java.io.IOException) {
            throw VoiceException(ErrorCode.NETWORK, "Backend unreachable: ${e.message}", tier, e)
        }
        val latency = System.currentTimeMillis() - t0
        if (!resp.ok) throw VoiceException(ErrorCode.BACKEND_ERROR, "Backend HTTP ${resp.status}: ${resp.text.take(300)}", tier)
        val ct = resp.header("Content-Type") ?: ""
        val audio: PcmAudio
        var voiceId = resp.header("X-Voice-Id") ?: options.voiceId ?: "default"
        var engine = resp.header("X-Voice-Engine") ?: "backend"
        if (ct.contains("json")) {
            val m = Json.parseObject(resp.text)
            val b64 = m.str("audioBase64") ?: throw VoiceException(ErrorCode.BACKEND_ERROR, "No audio in response", tier)
            val bytes = Base64.getDecoder().decode(b64)
            audio = if (m.str("format") == "pcm16") PcmAudio.fromBytesLE(bytes, m.int("sampleRate", 22050)) else Wav.decode(bytes)
            voiceId = m.str("voiceId") ?: voiceId; engine = m.str("engine") ?: engine
        } else {
            audio = Wav.decode(resp.body)
        }
        return TtsResult(audio, options.language, voiceId, tier, "hosted:$engine", latency)
    }
}
