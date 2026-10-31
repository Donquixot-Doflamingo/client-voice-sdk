package dev.indicvoice.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.Json
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.SttOptions
import dev.indicvoice.core.TranscriptResult
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.TtsResult
import dev.indicvoice.core.VoiceException
import dev.indicvoice.core.Wav
import dev.indicvoice.core.bool
import dev.indicvoice.core.double
import dev.indicvoice.core.int
import dev.indicvoice.core.obj
import dev.indicvoice.core.str
import dev.indicvoice.core.strList
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Reference tier-3 backend implementing the SDK's neutral contract (CONTRACT.md).
 *
 * Providers are chosen in proxy/config.json (no secrets there); vendor keys
 * come from the environment:
 *   SARVAM_API_KEY   enables the "sarvam" provider
 *   PROXY_TOKEN      if set, clients must send  Authorization: Bearer <token>
 *   PORT             listen port (default 8080)
 *
 * The "local" provider runs the same on-device models the SDK uses (tier 2)
 * on the server, which makes a completely free tier 3 for testing.
 */
object ProxyMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.toList().windowed(2, 1, true).filter { it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it.getOrElse(1) { "true" } }
        val configFile = opts["config"]?.let(::File) ?: listOf(File("proxy/config.json"), File("config.json"), File("../proxy/config.json")).firstOrNull { it.exists() }
        val cfg = ProxyConfig.load(configFile)
        val port = (opts["port"] ?: System.getenv("PORT") ?: cfg.port.toString()).toInt()
        val server = ProxyServer(cfg, Logger.stdout(cfg.logLevel))
        server.start(port)
        println("indicvoice proxy listening on http://0.0.0.0:$port  providers: stt=${server.sttProviders.map { it.id }} tts=${server.ttsProviders.map { it.id }}")
        println("  GET  /v1/health | GET /v1/voices?language=hi | GET /v1/catalog.json")
        println("  POST /v1/stt   (audio/wav or JSON) | POST /v1/tts (JSON)")
        Thread.currentThread().join()
    }
}

class ProxyConfig(
    val port: Int,
    val logLevel: String,
    val sttProviderIds: List<String>,
    val ttsProviderIds: List<String>,
    val packsDir: File,
    val sdkConfigFile: File?,
    val catalogFile: File?,
    val sarvam: Map<String, Any?>,
    val local: Map<String, Any?>,
    val maxAudioSeconds: Int,
    /** When set, every /v1/stt request's audio + result is saved here (last 50) — for debugging on a test machine only. */
    val debugAudioDir: File? = null,
    /** Where the accuracy-test clips live (default ~/.indicvoice/eval). */
    val evalDir: File? = null,
) {
    companion object {
        fun load(file: File?): ProxyConfig {
            val m = if (file != null && file.exists()) Json.parseObject(file.readText()) else emptyMap()
            val base = file?.absoluteFile?.parentFile ?: File(".")
            fun rel(p: String?): File? = p?.let {
                val expanded = if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it
                if (File(expanded).isAbsolute) File(expanded) else File(base, expanded)
            }
            val packs = rel(m.str("packsDir")) ?: System.getenv("INDICVOICE_PACKS_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".indicvoice/packs")
            return ProxyConfig(
                port = m.int("port", 8080),
                logLevel = m.str("logLevel") ?: "info",
                sttProviderIds = m.obj("stt")?.strList("providers")?.ifEmpty { null } ?: listOf("sarvam", "local"),
                ttsProviderIds = m.obj("tts")?.strList("providers")?.ifEmpty { null } ?: listOf("sarvam", "local"),
                packsDir = packs,
                sdkConfigFile = rel(m.str("sdkConfig")) ?: listOf(File(base, "../config/voice-config.json"), File("config/voice-config.json")).firstOrNull { it.exists() },
                catalogFile = rel(m.str("catalog")) ?: listOf(File(base, "../config/catalog.json"), File("config/catalog.json")).firstOrNull { it.exists() },
                sarvam = m.obj("sarvam") ?: emptyMap(),
                local = m.obj("local") ?: emptyMap(),
                maxAudioSeconds = m.int("maxAudioSeconds", 60),
                debugAudioDir = rel(m.str("debugAudioDir")?.takeIf { it.isNotBlank() }),
                evalDir = rel(m.str("evalDir")?.takeIf { it.isNotBlank() }),
            )
        }
    }
}

interface SttProvider {
    val id: String
    fun available(): Boolean
    fun supportsStt(language: Language?): Boolean
    fun transcribe(audio: PcmAudio, language: Language?, keyterms: List<String>): TranscriptResult
}

interface TtsProvider {
    val id: String
    fun available(): Boolean
    fun supportsTts(language: Language): Boolean
    fun voices(language: Language?): List<Map<String, Any?>>
    fun synthesize(text: String, language: Language, voiceId: String?, speed: Double, sampleRate: Int): TtsResult
}

class ProxyServer(private val cfg: ProxyConfig, private val log: Logger) {
    private val token = System.getenv("PROXY_TOKEN")?.trim().orEmpty()
    private val local = LocalProvider(cfg, log)
    private val sarvam = SarvamProvider(System.getenv("SARVAM_API_KEY")?.trim().orEmpty(), cfg.sarvam, log)
    val sttProviders: List<SttProvider> = cfg.sttProviderIds.mapNotNull { id -> when (id) { "sarvam" -> sarvam; "local" -> local; else -> null } }.filter { it.available() }
    val ttsProviders: List<TtsProvider> = cfg.ttsProviderIds.mapNotNull { id -> when (id) { "sarvam" -> sarvam; "local" -> local; else -> null } }.filter { it.available() }

    fun start(port: Int) {
        val server = HttpServer.create(InetSocketAddress(port), 64)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/v1/health") { ex -> handle(ex) { health() } }
        server.createContext("/v1/stt") { ex -> handle(ex) { stt(ex) } }
        server.createContext("/v1/tts") { ex -> handle(ex) { tts(ex) } }
        server.createContext("/v1/lid") { ex -> handle(ex) { lid(ex) } }
        server.createContext("/v1/eval") { ex -> handle(ex) { evaluation(ex) } }
        server.createContext("/v1/voices") { ex -> handle(ex) { voices(ex) } }
        server.createContext("/v1/catalog.json") { ex -> handle(ex) { catalog() } }
        server.createContext("/") { ex -> handle(ex) { when (ex.requestURI.path) { "/", "/index.html" -> console("index.html"); "/m", "/m/", "/msite.html" -> console("msite.html"); else -> Reply(404, "application/json", Json.stringify(mapOf("error" to "not found")).toByteArray()) } } }
        server.start()
    }

    class Reply(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private fun handle(ex: HttpExchange, fn: () -> Reply) {
        val t0 = System.currentTimeMillis()
        val reply = try {
            if (ex.requestMethod == "OPTIONS") Reply(204, "text/plain", ByteArray(0))
            else if (!authorized(ex)) Reply(401, "application/json", Json.stringify(mapOf("error" to "unauthorized")).toByteArray())
            else fn()
        } catch (e: VoiceException) {
            Reply(if (e.code == ErrorCode.BAD_AUDIO || e.code == ErrorCode.CONFIG_ERROR) 400 else 502, "application/json", Json.stringify(mapOf("error" to e.toJsonValue())).toByteArray())
        } catch (e: Exception) {
            log.error("request failed", e)
            Reply(500, "application/json", Json.stringify(mapOf("error" to mapOf("code" to "INTERNAL", "message" to e.toString()))).toByteArray())
        }
        try {
            val h = ex.responseHeaders
            h["Content-Type"] = listOf(reply.contentType)
            h["Access-Control-Allow-Origin"] = listOf("*")
            h["Access-Control-Allow-Headers"] = listOf("*")
            h["Access-Control-Allow-Methods"] = listOf("GET, POST, OPTIONS")
            for ((k, v) in reply.headers) h[k] = listOf(v)
            if (reply.status == 204) ex.sendResponseHeaders(204, -1) else { ex.sendResponseHeaders(reply.status, reply.body.size.toLong()); ex.responseBody.use { it.write(reply.body) } }
        } finally {
            ex.close()
            log.info("${ex.requestMethod} ${ex.requestURI.path} -> ${reply.status} (${System.currentTimeMillis() - t0} ms)")
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        if (token.isEmpty()) return true
        if (ex.requestURI.path in setOf("/v1/health", "/", "/index.html", "/m", "/m/", "/msite.html")) return true
        val auth = ex.requestHeaders.getFirst("Authorization") ?: return false
        return auth.removePrefix("Bearer ").trim() == token
    }

    private fun json(status: Int, value: Any?) = Reply(status, "application/json; charset=utf-8", Json.stringify(value).toByteArray(Charsets.UTF_8))

    private fun health() = json(200, linkedMapOf(
        "ok" to true, "service" to "indicvoice-proxy", "version" to "0.1",
        "providers" to mapOf("stt" to sttProviders.map { it.id }, "tts" to ttsProviders.map { it.id }),
        "auth" to if (token.isEmpty()) "none" else "bearer",
        "languages" to Language.entries.map { it.code },
    ))

    /** The browser pages (proxy/web/index.html = desktop console, msite.html = mobile test site at /m), bundled into the jar as resources. */
    private fun console(page: String): Reply {
        val local = listOf(File("proxy/web/$page"), File("../proxy/web/$page"), File("web/$page")).firstOrNull { it.exists() }
        val bytes = local?.readBytes() ?: ProxyServer::class.java.getResourceAsStream("/indicvoice/web/$page")?.use { it.readBytes() }
            ?: return json(404, mapOf("error" to "console not bundled"))
        return Reply(200, "text/html; charset=utf-8", bytes)
    }

    private fun catalog(): Reply {
        val f = cfg.catalogFile ?: return json(404, mapOf("error" to "no catalog configured"))
        return Reply(200, "application/json; charset=utf-8", f.readBytes())
    }

    private fun stt(ex: HttpExchange): Reply {
        if (ex.requestMethod != "POST") return json(405, mapOf("error" to "POST required"))
        val ct = ex.requestHeaders.getFirst("Content-Type") ?: "audio/wav"
        val body = ex.requestBody.readBytes()
        var language = Language.parse(ex.requestHeaders.getFirst("X-Voice-Language"))
        var keyterms = ex.requestHeaders.getFirst("X-Voice-Keyterms")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val audio: PcmAudio = when {
            ct.contains("json") -> {
                val m = Json.parseObject(String(body, Charsets.UTF_8))
                language = Language.parse(m.str("language")) ?: language
                keyterms = m.strList("keyterms").ifEmpty { keyterms }
                val bytes = Base64.getDecoder().decode(m.str("audioBase64") ?: throw VoiceException(ErrorCode.BAD_AUDIO, "audioBase64 missing"))
                if (m.str("encoding") == "wav") Wav.decode(bytes) else PcmAudio.fromBytesLE(bytes, m.int("sampleRate", 16000))
            }
            ct.contains("multipart") -> throw VoiceException(ErrorCode.BAD_AUDIO, "send audio/wav or JSON, not multipart")
            else -> Wav.decode(body)
        }
        if (audio.durationMs > cfg.maxAudioSeconds * 1000L) throw VoiceException(ErrorCode.BAD_AUDIO, "audio longer than ${cfg.maxAudioSeconds}s; use shorter utterances")
        val errors = ArrayList<String>()
        val pin = ex.requestHeaders.getFirst("X-Voice-Provider")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        for (p in sttProviders) {
            if (pin != null && p.id != pin) continue
            if (!p.supportsStt(language)) { errors += "${p.id}: unsupported language"; continue }
            try {
                val r = p.transcribe(audio.resampled(16000), language, keyterms)
                log.info("stt ${p.id}: ${audio.durationMs} ms audio, asked=${language?.code ?: "auto"} → ${r.language.language.code} (${r.language.source}) via ${r.engine}: ${r.text.take(80)}")
                saveDebug(audio, language, r.toJson())
                return json(200, r.toJsonValue())
            } catch (e: VoiceException) { errors += "${p.id}: ${e.message}"; log.warn("provider ${p.id} failed: ${e.message}") }
        }
        if (pin != null && errors.isEmpty()) errors += "provider '$pin' not enabled on this server"
        saveDebug(audio, language, "ERROR " + errors.joinToString("; "))
        return json(503, mapOf("error" to mapOf("code" to "NO_PROVIDER", "message" to errors.joinToString("; "))))
    }

    private fun tts(ex: HttpExchange): Reply {
        if (ex.requestMethod != "POST") return json(405, mapOf("error" to "POST required"))
        val m = Json.parseObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
        val text = m.str("text")?.trim().orEmpty()
        if (text.isEmpty()) throw VoiceException(ErrorCode.CONFIG_ERROR, "text is required")
        val language = Language.parse(m.str("language")) ?: Language.HINDI
        val voiceId = m.str("voiceId")?.takeIf { it.isNotBlank() && it != "default" }
        val speed = m.double("speed", 1.0)
        val sampleRate = m.int("sampleRate", 22050)
        val wantJson = (ex.requestHeaders.getFirst("Accept") ?: "").contains("application/json") || m.bool("json", false)
        val errors = ArrayList<String>()
        val pin = ex.requestHeaders.getFirst("X-Voice-Provider")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        for (p in ttsProviders) {
            if (pin != null && p.id != pin) continue
            if (!p.supportsTts(language)) { errors += "${p.id}: unsupported language"; continue }
            if (voiceId != null && voiceId.contains(':') && !voiceId.startsWith(p.id + ":")) continue // voice pinned to another provider
            try {
                val r = p.synthesize(text, language, voiceId?.substringAfter(':'), speed, sampleRate)
                val wav = r.audio.toWavBytes()
                return if (wantJson) json(200, mapOf("audioBase64" to Base64.getEncoder().encodeToString(wav), "format" to "wav", "sampleRate" to r.audio.sampleRate, "voiceId" to r.voiceId, "engine" to r.engine, "latencyMs" to r.latencyMs))
                else Reply(200, "audio/wav", wav, mapOf("X-Voice-Id" to r.voiceId, "X-Voice-Engine" to r.engine, "X-Voice-Latency-Ms" to r.latencyMs.toString()))
            } catch (e: VoiceException) { errors += "${p.id}: ${e.message}"; log.warn("provider ${p.id} failed: ${e.message}") }
        }
        return json(503, mapOf("error" to mapOf("code" to "NO_PROVIDER", "message" to errors.joinToString("; "))))
    }

    private val evaluator = Evaluator(cfg.evalDir ?: File(System.getProperty("user.home"), ".indicvoice/eval"), local, log)

    /**
     * Accuracy test with your own voice (test machines only):
     *   POST   /v1/eval        JSON {text, language, audioBase64 (wav)} → stores one prompted clip
     *   GET    /v1/eval        → list of stored clips
     *   POST   /v1/eval/run    → scores Auto mode + every installed STT pack on all clips
     *   DELETE /v1/eval        → removes the clips
     */
    private fun evaluation(ex: HttpExchange): Reply = when {
        ex.requestURI.path.endsWith("/run") -> json(200, evaluator.run())
        ex.requestMethod == "POST" -> {
            val m = Json.parseObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
            val lang = Language.parse(m.str("language")) ?: throw VoiceException(ErrorCode.CONFIG_ERROR, "language required")
            val text = m.str("text")?.takeIf { it.isNotBlank() } ?: throw VoiceException(ErrorCode.CONFIG_ERROR, "text required")
            val audio = Wav.decode(Base64.getDecoder().decode(m.str("audioBase64") ?: throw VoiceException(ErrorCode.BAD_AUDIO, "audioBase64 missing")))
            json(200, evaluator.save(audio, lang, text))
        }
        ex.requestMethod == "DELETE" -> json(200, evaluator.clear())
        else -> json(200, mapOf("clips" to evaluator.clips().map { mapOf("id" to it.id, "language" to it.language.code, "text" to it.reference, "audioMs" to it.audio.durationMs) }))
    }

    /** Spoken language identification only: WAV in → {language, raw, latencyMs}. Optional part of the contract. */
    private fun lid(ex: HttpExchange): Reply {
        if (ex.requestMethod != "POST") return json(405, mapOf("error" to "POST required"))
        val audio = Wav.decode(ex.requestBody.readBytes()).resampled(16000)
        val r = local.identifyLanguage(audio)
        log.info("lid: ${audio.durationMs} ms → ${r["raw"]} (${r["latencyMs"]} ms)")
        return json(if (r["available"] == true) 200 else 503, r)
    }

    private val debugCounter = java.util.concurrent.atomic.AtomicInteger()
    private fun saveDebug(audio: PcmAudio, language: Language?, result: String) {
        val dir = cfg.debugAudioDir ?: return
        try {
            dir.mkdirs()
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val base = "$stamp-${debugCounter.incrementAndGet()}-${language?.code ?: "auto"}"
            File(dir, "$base.wav").writeBytes(audio.toWavBytes())
            File(dir, "$base.json").writeText(result)
            dir.listFiles()?.filter { it.name.endsWith(".wav") }?.sortedBy { it.name }?.dropLast(50)?.forEach { it.delete(); File(dir, it.name.removeSuffix(".wav") + ".json").delete() }
        } catch (e: Exception) { log.warn("debug save failed: ${e.message}") }
    }

    private fun voices(ex: HttpExchange): Reply {
        val q = ex.requestURI.query?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=', "") } ?: emptyMap()
        val language = Language.parse(q["language"])
        return json(200, mapOf("voices" to ttsProviders.flatMap { p -> p.voices(language).map { v -> v + mapOf("id" to "${p.id}:${v["id"]}", "provider" to p.id) } }))
    }
}
