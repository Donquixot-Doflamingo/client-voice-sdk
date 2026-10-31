package dev.indicvoice.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What a platform (Android, JVM, iOS shim) must provide to the core. */
interface Platform {
    val name: String
    val logger: Logger
    val packsDir: File
    /** Tier 1 engines; null where the OS has none (desktop JVM). */
    fun osStt(): SttEngine?
    fun osTts(): TtsEngine?
    /** Tier 2 factory; null when the sherpa-onnx module is not on the classpath. */
    fun onDevice(): OnDeviceEngineFactory?
    fun audioSource(sampleRate: Int): AudioSource?
    fun audioSink(): AudioSink?
    fun deviceInfo(): Map<String, Any?>
    /** Release OS engines (TTS bindings, executors). Called by [IndicVoice.close]. */
    fun close() {}
}

/**
 * The SDK facade. One instance per app. Create it with a [VoiceConfig]
 * (from voice-config.json) and a [Platform].
 */
class IndicVoice(val config: VoiceConfig, private val platform: Platform) {

    val log: Logger get() = platform.logger
    val backend = BackendSettings()
    val packs = PackManager(
        storageDir = if (config.packs.storageDir.isNotBlank()) File(config.packs.storageDir.replaceFirst(Regex("^~/"), System.getProperty("user.home", "~") + "/")) else platform.packsDir,
        inlineCatalog = config.packs.catalogInline,
        catalogUrl = config.packs.catalogUrl,
        log = log,
    )

    private val enabled = HashMap<Feature, Boolean>().also { for (f in Feature.entries) it[f] = f in config.features }
    private val hostedStt = HostedSttEngine(config.stt.tier3, backend, log)
    private val hostedTts = HostedTtsEngine(config.tts.tier3, backend, log)
    private val loadedStt = LinkedHashMap<String, SttEngine>()
    private val loadedTts = LinkedHashMap<String, TtsEngine>()
    private var vad: Vad? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "indicvoice-worker").apply { isDaemon = true } }

    // ------------------------------------------------------------ switches

    fun isEnabled(feature: Feature): Boolean = enabled[feature] == true

    /** Per-user / per-session switch, e.g. voice.setFeatureEnabled(Feature.TTS, false). */
    fun setFeatureEnabled(feature: Feature, on: Boolean) { enabled[feature] = on }

    /** Inject the backend at runtime (from the app's UI or .env). Empty URL disables tier 3. */
    fun setBackend(url: String?, token: String? = null, extraHeaders: Map<String, String> = emptyMap()) {
        backend.url = url?.trim() ?: ""
        backend.token = token?.trim() ?: ""
        backend.extraHeaders = extraHeaders
    }

    fun backendUrl(): String = backend.resolvedUrl(config.stt.tier3.backendUrl)

    // ------------------------------------------------------------ tiers

    private fun sttOrder(): List<Tier> = when (config.stt.tierPolicy) {
        TierPolicy.DEVICE_FIRST -> listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED)
        TierPolicy.DEVICE_ONLY -> listOf(Tier.OS, Tier.ON_DEVICE)
        TierPolicy.HOSTED_FIRST -> listOf(Tier.HOSTED, Tier.OS, Tier.ON_DEVICE)
        TierPolicy.HOSTED_ONLY -> listOf(Tier.HOSTED)
        TierPolicy.ORDER -> config.stt.tierOrder
    }

    private fun ttsOrder(): List<Tier> = when (config.tts.voicePolicy) {
        VoicePolicy.OS_FIRST -> listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED)
        VoicePolicy.CONSISTENT -> listOf(Tier.ON_DEVICE, Tier.HOSTED, Tier.OS)
        VoicePolicy.DEVICE_ONLY -> listOf(Tier.OS, Tier.ON_DEVICE)
        VoicePolicy.HOSTED_FIRST -> listOf(Tier.HOSTED, Tier.OS, Tier.ON_DEVICE)
        VoicePolicy.HOSTED_ONLY -> listOf(Tier.HOSTED)
        VoicePolicy.ORDER -> config.tts.tierOrder
    }

    /** Resolves the STT engine for a tier, or returns the reason it is unavailable. */
    /**
     * Resolves the STT engine for a tier, or returns the reason it is unavailable.
     * With [load] = false a tier-2 pack is only checked, not loaded into memory.
     */
    fun sttEngine(tier: Tier, language: Language?, packId: String? = null, load: Boolean = true): Pair<SttEngine?, Availability> = when (tier) {
        Tier.OS -> {
            if (!config.stt.tier1Enabled) null to Availability.no("tier1 disabled in config")
            else platform.osStt()?.let { it to it.availability(language) } ?: (null to Availability.no("no OS speech engine on ${platform.name}"))
        }
        Tier.ON_DEVICE -> onDeviceStt(language, packId, load)
        Tier.HOSTED -> hostedStt to hostedStt.availability(language)
    }

    @Synchronized
    private fun onDeviceStt(language: Language?, packId: String?, load: Boolean = true): Pair<SttEngine?, Availability> {
        if (!config.stt.tier2Enabled) return null to Availability.no("tier2 disabled in config")
        val factory = platform.onDevice() ?: return null to Availability.no("on-device runtime (sherpa-onnx) not bundled")
        factory.isRuntimeAvailable().let { if (!it.available) return null to it }
        val id = packId ?: config.stt.packFor(language) ?: return null to Availability.no("no pack configured for ${language?.code ?: "auto"}")
        val spec = packs.spec(id) ?: return null to Availability.no("pack '$id' not in catalog")
        if (language != null && !spec.supports(language)) {
            // Try the catalog for another installed STT pack that knows the language.
            val alt = packs.catalog().firstOrNull { it.kind == "stt" && it.supports(language) && packs.isInstalled(it.id) }
                ?: return null to Availability.no("pack '$id' does not support ${language.code}", mapOf("packId" to id))
            return if (load) loadStt(factory, alt) else null to Availability(true, null, mapOf("packId" to alt.id, "engine" to "sherpa:${alt.id}", "loaded" to (alt.id in loadedStt)))
        }
        if (!packs.isInstalled(id)) return null to Availability.no("pack '$id' not downloaded", mapOf("packId" to id, "sizeMb" to Math.round(spec.sizeBytes / 1048576.0), "downloadable" to true))
        if (!load) return loadedStt[id]?.let { it to Availability(true, null, mapOf("packId" to id, "engine" to it.id, "loaded" to true)) }
            ?: (null to Availability(true, null, mapOf("packId" to id, "engine" to "sherpa:$id", "loaded" to false)))
        return loadStt(factory, spec)
    }

    private fun loadStt(factory: OnDeviceEngineFactory, spec: PackSpec): Pair<SttEngine?, Availability> {
        loadedStt[spec.id]?.let { return it to Availability(true, null, mapOf("packId" to spec.id, "engine" to spec.engine, "cached" to true)) }
        return try {
            val t0 = System.currentTimeMillis()
            val e = factory.stt(spec, packs.dirFor(spec.id), config.stt.tier2NumThreads)
            log.info("loaded STT pack ${spec.id} in ${System.currentTimeMillis() - t0} ms")
            evictIfNeeded(loadedStt, config.stt.tier2MaxLoadedPacks)
            loadedStt[spec.id] = e
            e to Availability(true, null, mapOf("packId" to spec.id, "engine" to spec.engine, "loadMs" to (System.currentTimeMillis() - t0)))
        } catch (e: Exception) {
            log.error("failed to load pack ${spec.id}", e)
            null to Availability.no("pack '${spec.id}' failed to load: ${e.message}", mapOf("packId" to spec.id))
        }
    }

    /** Engines currently used by a live session; never evicted. */
    private val pinned: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun <T : Any> evictIfNeeded(map: LinkedHashMap<String, T>, max: Int) {
        val victims = map.entries.filter { it.value !in pinned }.map { it.key }.iterator()
        while (map.size >= max && victims.hasNext()) {
            val key = victims.next()
            val e = map.remove(key)
            (e as? SttEngine)?.close(); (e as? TtsEngine)?.close()
            log.info("evicted pack $key")
        }
    }

    fun ttsEngine(tier: Tier, language: Language, packId: String? = null, load: Boolean = true): Pair<TtsEngine?, Availability> = when (tier) {
        Tier.OS -> {
            if (!config.tts.tier1Enabled) null to Availability.no("tier1 disabled in config")
            else platform.osTts()?.let { it to it.availability(language) } ?: (null to Availability.no("no OS TTS engine on ${platform.name}"))
        }
        Tier.ON_DEVICE -> onDeviceTts(language, packId, load)
        Tier.HOSTED -> hostedTts to hostedTts.availability(language)
    }

    @Synchronized
    private fun onDeviceTts(language: Language, packId: String?, load: Boolean = true): Pair<TtsEngine?, Availability> {
        if (!config.tts.tier2Enabled) return null to Availability.no("tier2 disabled in config")
        val factory = platform.onDevice() ?: return null to Availability.no("on-device runtime (sherpa-onnx) not bundled")
        factory.isRuntimeAvailable().let { if (!it.available) return null to it }
        val id = packId ?: config.tts.packFor(language) ?: return null to Availability.no("no TTS pack configured for ${language.code}")
        val spec = packs.spec(id) ?: return null to Availability.no("pack '$id' not in catalog")
        if (!spec.supports(language)) return null to Availability.no("pack '$id' has no ${language.code} voice")
        if (!packs.isInstalled(id)) return null to Availability.no("pack '$id' not downloaded", mapOf("packId" to id, "sizeMb" to Math.round(spec.sizeBytes / 1048576.0), "downloadable" to true))
        loadedTts[id]?.let { return it to Availability(true, null, mapOf("packId" to id, "engine" to it.id, "loaded" to true)) }
        if (!load) return null to Availability(true, null, mapOf("packId" to id, "engine" to "sherpa:$id", "loaded" to false))
        return try {
            val t0 = System.currentTimeMillis()
            val e = factory.tts(spec, packs.dirFor(id), config.tts.tier2NumThreads)
            evictIfNeeded(loadedTts, 2)
            loadedTts[id] = e
            e to Availability(true, null, mapOf("packId" to id, "loadMs" to (System.currentTimeMillis() - t0)))
        } catch (e: Exception) {
            null to Availability.no("pack '$id' failed to load: ${e.message}")
        }
    }

    // ------------------------------------------------------------ capabilities

    /** Everything the UI needs to show per tier: available?, why not, which pack/engine. */
    fun capabilities(language: Language? = null): Map<String, Any?> {
        val lang = language ?: config.defaultLanguage
        val stt = LinkedHashMap<String, Any?>()
        for (t in Tier.entries) {
            val (engine, av) = sttEngine(t, language, load = false)
            stt["tier${t.number}"] = linkedMapOf("tier" to t.number, "label" to t.label, "engine" to (engine?.id ?: av.details["engine"]), "availability" to av.toJsonValue())
        }
        val tts = LinkedHashMap<String, Any?>()
        for (t in Tier.entries) {
            val (engine, av) = ttsEngine(t, lang, load = false)
            tts["tier${t.number}"] = linkedMapOf("tier" to t.number, "label" to t.label, "engine" to (engine?.id ?: av.details["engine"]), "availability" to av.toJsonValue())
        }
        return linkedMapOf(
            "platform" to platform.name,
            "device" to platform.deviceInfo(),
            "language" to (language?.code ?: "auto"),
            "features" to Feature.entries.associate { it.name to isEnabled(it) },
            "backendUrl" to backendUrl(),
            "stt" to linkedMapOf("policy" to config.stt.tierPolicy.name, "order" to sttOrder().map { it.number }, "tiers" to stt),
            "tts" to linkedMapOf("policy" to config.tts.voicePolicy.name, "order" to ttsOrder().map { it.number }, "tiers" to tts),
            "packs" to packs.listStatus(),
        )
    }

    // ------------------------------------------------------------ STT (batch)

    /** Transcribes a complete utterance. Follows the tier policy unless options.tier is set. */
    fun transcribe(audio: PcmAudio, options: SttOptions = SttOptions()): TranscriptResult {
        if (!isEnabled(Feature.STT)) throw VoiceException(ErrorCode.FEATURE_DISABLED, "STT is disabled")
        val opts = options.copy(allowedLanguages = options.allowedLanguages.ifEmpty { config.allowedLanguages })
        val tiers = if (opts.tier != null) listOf(opts.tier) else sttOrder()
        val reasons = ArrayList<String>()
        var lastError: VoiceException? = null
        for (t in tiers) {
            val (engine, av) = sttEngine(t, opts.language, if (t == Tier.ON_DEVICE) opts.packId else null)
            if (engine == null || !av.available) { reasons += "tier${t.number}: ${av.reason}"; continue }
            try {
                val r = if (t == Tier.ON_DEVICE && opts.language == null && opts.packId == null) transcribeAuto(audio, opts, engine)
                        else engine.transcribe(audio, opts)
                if (opts.tier == null && config.stt.fallbackOnLowConfidence > 0 && (r.confidence ?: 1.0) < config.stt.fallbackOnLowConfidence && t != tiers.last()) {
                    reasons += "tier${t.number}: low confidence ${r.confidence}"; continue
                }
                return r
            } catch (e: VoiceException) {
                lastError = e; reasons += "tier${t.number}: ${e.code} ${e.message}"
                log.warn("tier ${t.number} failed: ${e.message}")
                if (!config.stt.fallbackOnError && opts.tier == null) throw e
            } catch (e: Exception) {
                lastError = VoiceException(ErrorCode.ENGINE_FAILED, e.message ?: e.toString(), t, e); reasons += "tier${t.number}: ${e.message}"
                log.error("tier ${t.number} crashed", e)
            }
        }
        throw VoiceException(ErrorCode.NO_ENGINE, "No tier could transcribe (" + reasons.joinToString("; ") + ")", null, lastError)
    }

    // ------------------------------------------------------------ auto language (tier 2)

    @Volatile private var lid: LanguageIdentifier? = null
    @Volatile private var lidUnavailable: String? = null

    /** The spoken-language identifier (multilingual Whisper pack from stt.languageId), loaded on first use. */
    fun languageIdentifier(): LanguageIdentifier? {
        lid?.let { return it }
        val c = config.stt.languageId
        if (!c.enabled || c.pack.isBlank()) return null
        synchronized(this) {
            lid?.let { return it }
            val spec = packs.spec(c.pack) ?: return null.also { lidUnavailable = "pack '${c.pack}' not in catalog" }
            if (!packs.isInstalled(c.pack)) { lidUnavailable = "pack '${c.pack}' not downloaded"; return null }
            if (lidUnavailable?.startsWith("failed") == true) return null
            return try {
                val t0 = System.currentTimeMillis()
                platform.onDevice()?.lid(spec, packs.dirFor(c.pack), config.stt.tier2NumThreads)?.also {
                    lid = it; lidUnavailable = null
                    log.info("loaded language-ID pack ${c.pack} in ${System.currentTimeMillis() - t0} ms")
                }
            } catch (e: Exception) { lidUnavailable = "failed to load ${c.pack}: ${e.message}"; log.warn(lidUnavailable!!); null }
        }
    }

    /**
     * Identifies the spoken language from audio alone. Returns the raw model answer, the language it maps to
     * (null when not one of the allowed languages), and the time taken. Used by auto mode and by the backend's /v1/lid.
     */
    fun identifyLanguage(audio: PcmAudio, allowed: List<Language> = config.allowedLanguages): Map<String, Any?> {
        val id = languageIdentifier() ?: return linkedMapOf("available" to false, "reason" to (lidUnavailable ?: "language ID disabled"))
        val t0 = System.nanoTime()
        val raw = id.identify(if (audio.sampleRate == 16000) audio else audio.resampled(16000))
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lang = Language.parse(raw)?.takeIf { allowed.isEmpty() || it in allowed }
        return linkedMapOf("available" to true, "raw" to raw, "language" to lang?.code, "allowed" to (lang != null), "engine" to id.id, "latencyMs" to ms)
    }

    /** How believable a transcript is: empty or written in a foreign script (Japanese for English speech, …) scores ~0. */
    private fun plausibility(r: TranscriptResult): Double {
        if (r.text.isBlank()) return 0.0
        val foreign = LanguageDetector.foreignLetterShare(r.text)
        if (foreign > 0.3) return 0.05
        // A sentence in one language is written in one script (plus Latin for loanwords): "ना بستی" is a misfire.
        val stats = LanguageDetector.scriptStats(r.text)
        val indic = stats.counts.filterKeys { it != Script.LATIN }.values.sortedDescending()
        val mixedPenalty = if (indic.size >= 2) 0.5 * indic[0].toDouble() / (indic[0] + indic[1]) else 1.0
        return (r.language.confidence * (1 - foreign) * mixedPenalty).coerceIn(0.1, 1.0)
    }

    /**
     * Tier-2 auto mode. 1) identify the spoken language from the audio (Whisper LID);
     * 2) transcribe with that language's pack; 3) if the language is not allowed / not identified, or the
     * output is implausible, try the fallback languages (English, Hindi by default) and keep the most plausible.
     */
    private fun transcribeAuto(audio: PcmAudio, opts: SttOptions, autoEngine: SttEngine?): TranscriptResult {
        val c = config.stt.languageId
        val allowed = opts.allowedLanguages
        fun ok(l: Language?) = l != null && (allowed.isEmpty() || l in allowed)
        val t0 = System.nanoTime()
        var raw: String? = null
        if (audio.durationMs >= c.minAudioMs && audio.rmsDb() > -55) {
            try { languageIdentifier()?.let { raw = it.identify(audio) } } catch (e: Exception) { log.warn("language ID failed: ${e.message}") }
        }
        val lidMs = (System.nanoTime() - t0) / 1_000_000
        val spoken = raw?.let { Language.parse(it) }?.takeIf { ok(it) }
        val route = spoken?.let { Language.parse(c.routeAs[it.code]) ?: it }?.takeIf { ok(it) } ?: spoken
        if (raw != null) log.info("language ID: '$raw' → ${spoken?.code ?: "not allowed"}${if (route != spoken) " (via ${route?.code} pack)" else ""} in $lidMs ms")

        val tried = HashSet<String>()
        var best: TranscriptResult? = null; var bestScore = -1.0
        // Does the script written match the language heard? (Hindi heard but Tamil written = a misfire somewhere.)
        fun agreesWithLid(r: TranscriptResult): Boolean {
            if (spoken == null) return true
            val script = r.language.script
            return script == spoken.script || script == route?.script || script == Script.UNKNOWN
        }
        fun attempt(lang: Language?, engineOverride: SttEngine? = null, checkLid: Boolean = true): TranscriptResult? {
            val engine = engineOverride ?: sttEngine(Tier.ON_DEVICE, lang, null).let { (e, av) -> if (e != null && av.available) e else null } ?: return null
            // CTC packs ignore the language hint, so one run per pack; Whisper depends on the language it is given.
            if (!tried.add(engine.id + if (engine.id.contains("whisper")) "/" + (lang?.code ?: "auto") else "")) return null
            val r = try { engine.transcribe(audio, opts.copy(language = lang)) } catch (e: Exception) { log.warn("auto: ${engine.id} failed: ${e.message}"); return null }
            // Re-label the language: the engine saw a hint, but the answer is "what was spoken" (LID) checked against the script written.
            val det = LanguageDetector.detect(r.text, engineLang = spoken?.code, hint = null, allowed = allowed)
            val labelled = r.copy(language = if (spoken != null && det.language == spoken) det.copy(source = "lid", confidence = maxOf(det.confidence, 0.9)) else det,
                                  latencyMs = r.latencyMs + lidMs)
            // When the audio was identified as an allowed language, answers in another language need to be clearly better.
            val score = plausibility(labelled) *
                (if (spoken != null && lang != null && lang != route && lang != spoken) 0.7 else 1.0) *
                (if (checkLid && !agreesWithLid(labelled)) 0.6 else 1.0)
            if (score > bestScore) { best = labelled; bestScore = score }
            return labelled
        }

        if (route != null) {
            val r = attempt(route)
            if (r != null && plausibility(r) >= 0.5 && agreesWithLid(r)) return r
            if (r != null && !agreesWithLid(r) && autoEngine != null) {
                // LID and the routed pack disagree: ask the all-language auto pack, which picks the script itself.
                log.info("auto: heard ${spoken?.code} but ${r.engine} wrote ${r.language.script.iso15924}; asking ${autoEngine.id}")
                attempt(null, autoEngine, checkLid = false)
            }
        } else if (raw == null && autoEngine != null) {
            // No language ID available: the configured auto pack first, as before.
            val r = attempt(null, autoEngine)
            if (r != null && plausibility(r) >= 0.5 && r.language.confidence >= 0.5) return r
        }
        // Same language, another installed pack (e.g. Omnilingual when Dolphin misfires on a single word).
        if (route != null && bestScore < 0.5) {
            packs.catalog().filter { it.kind == "stt" && it.supports(route) && packs.isInstalled(it.id) && "sherpa:${it.id}" !in tried.map { t -> t.substringBefore('/') } }
                .take(1).forEach { spec -> loadStt(platform.onDevice() ?: return@forEach, spec).first?.let { attempt(route, it) } }
        }
        for (code in c.fallbackLanguages + config.defaultLanguage.code) {
            if (bestScore >= 0.85) break
            val l = Language.parse(code)?.takeIf { ok(it) } ?: continue
            attempt(l)
        }
        return best ?: throw VoiceException(ErrorCode.NO_ENGINE, "auto mode: no installed pack could transcribe (language ID: ${raw ?: lidUnavailable ?: "off"})", Tier.ON_DEVICE)
    }

    /** Runs the same audio through every requested tier and reports side by side. */
    fun benchmark(audio: PcmAudio, options: SttOptions = SttOptions(), tiers: List<Tier> = Tier.entries.toList(), reference: String? = null): Map<String, Any?> {
        val opts = options.copy(allowedLanguages = options.allowedLanguages.ifEmpty { config.allowedLanguages })
        val rows = ArrayList<Map<String, Any?>>()
        for (t in tiers) {
            val row = linkedMapOf<String, Any?>("tier" to t.number, "label" to t.label)
            val (engine, av) = sttEngine(t, opts.language, if (t == Tier.ON_DEVICE) opts.packId else null)
            row["available"] = av.available
            row["reason"] = av.reason
            row["engine"] = engine?.id
            row["details"] = av.details
            if (engine != null && av.available) {
                try {
                    val t0 = System.nanoTime()
                    val r = if (t == Tier.ON_DEVICE && opts.language == null && opts.packId == null) transcribeAuto(audio, opts.copy(tier = t), engine)
                            else engine.transcribe(audio, opts.copy(tier = t))
                    val wall = (System.nanoTime() - t0) / 1_000_000
                    row["ok"] = true
                    row["result"] = r.toJsonValue()
                    row["wallMs"] = wall
                    if (reference != null) {
                        row["wer"] = Math.round(Metrics.wer(reference, r.text) * 1000) / 1000.0
                        row["cer"] = Math.round(Metrics.cer(reference, r.text) * 1000) / 1000.0
                    }
                } catch (e: VoiceException) {
                    row["ok"] = false; row["error"] = e.toJsonValue()
                } catch (e: Exception) {
                    row["ok"] = false; row["error"] = mapOf("code" to "ENGINE_FAILED", "message" to e.toString())
                }
            } else row["ok"] = false
            rows += row
        }
        return linkedMapOf(
            "audioMs" to audio.durationMs, "sampleRate" to audio.sampleRate, "rmsDb" to Math.round(audio.rmsDb() * 10) / 10.0,
            "language" to (opts.language?.code ?: "auto"), "reference" to reference, "device" to platform.deviceInfo(), "tiers" to rows,
        )
    }

    // ------------------------------------------------------------ STT (live)

    /**
     * Live microphone recognition. Tier 1 uses the OS engine's own listening
     * session when it can; tiers 2/3 record through [Platform.audioSource],
     * cut utterances with the VAD and transcribe each segment.
     */
    fun startLive(options: SttOptions = SttOptions(), listener: LiveSttListener): LiveSttSession {
        if (!isEnabled(Feature.STT)) throw VoiceException(ErrorCode.FEATURE_DISABLED, "STT is disabled")
        val opts = options.copy(allowedLanguages = options.allowedLanguages.ifEmpty { config.allowedLanguages })
        val tiers = if (opts.tier != null) listOf(opts.tier) else sttOrder()
        val reasons = ArrayList<String>()
        for (t in tiers) {
            val (engine, av) = sttEngine(t, opts.language, if (t == Tier.ON_DEVICE) opts.packId else null)
            if (engine == null || !av.available) { reasons += "tier${t.number}: ${av.reason}"; continue }
            if (engine is LiveCapable) {
                val fb = FallbackListener(listener, opts, tiers.drop(tiers.indexOf(t) + 1))
                val switchable = SwitchableSession(engine.startLive(opts, fb))
                fb.session = switchable
                return switchable
            }
            val source = platform.audioSource(16000) ?: run { reasons += "tier${t.number}: no microphone source"; continue }
            return SegmentedLiveSession(t, engine, source, opts, listener)
        }
        throw VoiceException(ErrorCode.NO_ENGINE, "No tier can listen (" + reasons.joinToString("; ") + ")")
    }

    /** A live session handle whose inner session can be swapped when tier 1 falls back to another tier. */
    private class SwitchableSession(@Volatile var inner: LiveSttSession) : LiveSttSession {
        @Volatile var stopRequested = false
        override val tier: Tier get() = inner.tier
        override fun stop() { stopRequested = true; inner.stop() }
        override fun cancel() { stopRequested = true; inner.cancel() }
    }

    /**
     * Wraps a tier-1 live session so an OS-engine failure falls through to the next tier.
     * The replacement session is started off the OS callback thread (model loads take seconds)
     * and installed into the caller's [SwitchableSession] so stop()/cancel() keep working.
     */
    private inner class FallbackListener(private val inner: LiveSttListener, private val opts: SttOptions, private val remaining: List<Tier>) : LiveSttListener {
        @Volatile var session: SwitchableSession? = null
        private val fellBack = AtomicBoolean(false)
        override fun onReady() = inner.onReady()
        override fun onSpeechStart() = inner.onSpeechStart()
        override fun onSpeechEnd() = inner.onSpeechEnd()
        override fun onLevel(rmsDb: Double) = inner.onLevel(rmsDb)
        override fun onPartial(text: String, language: DetectedLanguage?) = inner.onPartial(text, language)
        override fun onFinal(result: TranscriptResult) = inner.onFinal(result)
        override fun onTierChanged(tier: Tier, reason: String) = inner.onTierChanged(tier, reason)
        /** The old OS session's teardown must not end the replacement session. */
        override fun onStopped() { if (!fellBack.get()) inner.onStopped() }
        override fun onError(error: VoiceException) {
            val noFallback = !config.stt.fallbackOnError || remaining.isEmpty() || opts.tier != null ||
                error.code == ErrorCode.PERMISSION_DENIED || error.code == ErrorCode.CANCELLED || error.code == ErrorCode.BAD_AUDIO
            if (noFallback || !fellBack.compareAndSet(false, true)) { inner.onError(error); return }
            log.warn("tier ${error.tier?.number} live failed (${error.message}); falling back")
            safeExecute {
                try {
                    val next = remaining.first()
                    inner.onTierChanged(next, error.message ?: error.code.name)
                    val replacement = startLive(opts.copy(tier = next), inner)
                    val s = session
                    if (s == null) replacement.cancel() else { s.inner = replacement; if (s.stopRequested) replacement.cancel() }
                } catch (e: VoiceException) { inner.onError(e); inner.onStopped() }
                catch (e: Exception) { inner.onError(VoiceException(ErrorCode.ENGINE_FAILED, e.toString(), null, e)); inner.onStopped() }
            }
        }
    }

    /** Runs on the worker; never throws RejectedExecutionException into audio threads after close(). */
    private fun safeExecute(block: () -> Unit) {
        try { worker.execute(block) } catch (e: java.util.concurrent.RejectedExecutionException) { log.warn("worker closed; dropping task") }
    }

    private inner class SegmentedLiveSession(
        override val tier: Tier,
        private val engine: SttEngine,
        private val source: AudioSource,
        private val opts: SttOptions,
        private val listener: LiveSttListener,
    ) : LiveSttSession {
        private val stopped = AtomicBoolean(false)
        private val segmenter: Segmenter

        init {
            pinned += engine   // an engine in use by a live session must not be evicted/closed
            val v = vadInstance()
            val live = config.stt.live
            segmenter = Segmenter(v, 16000, live.endpointSilenceMs, live.maxUtteranceMs, live.minSpeechMs,
                onSpeechStart = { listener.onSpeechStart() },
                onSegment = { seg ->
                    listener.onSpeechEnd()
                    safeExecute {
                        if (stopped.get() && seg.samples.isEmpty()) return@safeExecute
                        try {
                            listener.onFinal(if (tier == Tier.ON_DEVICE && opts.language == null && opts.packId == null) transcribeAuto(seg, opts.copy(tier = tier), engine)
                                             else engine.transcribe(seg, opts.copy(tier = tier)))
                        }
                        catch (e: VoiceException) { listener.onError(e) }
                        catch (e: Exception) { listener.onError(VoiceException(ErrorCode.ENGINE_FAILED, e.toString(), tier, e)) }
                    }
                })
            listener.onTierChanged(tier, "live session on ${engine.id}")
            try {
                source.start { frame ->
                    listener.onLevel(PcmAudio(frame, 16000).rmsDb())
                    segmenter.accept(frame)
                }
            } catch (e: Exception) { pinned -= engine; throw e }
            listener.onReady()
        }

        override fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            source.stop(); segmenter.flush()
            safeExecute { pinned -= engine; listener.onStopped() }
        }

        override fun cancel() {
            if (!stopped.compareAndSet(false, true)) return
            source.stop(); pinned -= engine; listener.onStopped()
        }
    }

    @Synchronized
    private fun vadInstance(): Vad {
        vad?.let { it.reset(); return it }
        val factory = platform.onDevice()
        val spec = packs.spec(config.stt.tier2VadPack)
        val v: Vad = if (factory != null && spec != null && packs.isInstalled(spec.id)) {
            try { factory.vad(spec, packs.dirFor(spec.id), config.stt.live.vadThreshold) } catch (e: Exception) { log.warn("VAD pack failed (${e.message}); using energy VAD"); EnergyVad() }
        } else EnergyVad()
        vad = v
        return v
    }

    // ------------------------------------------------------------ TTS

    fun voices(language: Language? = null): List<VoiceInfo> {
        val out = ArrayList<VoiceInfo>()
        platform.osTts()?.let { out += it.voices(language) }
        for (spec in packs.catalog().filter { it.kind == "tts" && (language == null || it.supports(language)) }) {
            for (l in (if (language != null) listOf(language) else spec.languages)) {
                // Multi-speaker packs (Kokoro) list each speaker as its own voice: "pack:<id>#<speaker>".
                val speakers = ((spec.raw["voices"] as? Map<*, *>)?.get(l.code) as? Map<*, *>)?.keys?.map { it.toString() }.orEmpty()
                if (speakers.isEmpty()) out += VoiceInfo("pack:${spec.id}", l, Tier.ON_DEVICE, spec.name, spec.raw.str("gender"), true, spec.raw.str("quality"), packs.isInstalled(spec.id))
                for (sp in speakers) {
                    val gender = when (sp.getOrNull(1)) { 'f' -> "female"; 'm' -> "male"; else -> null }
                    val accent = when (sp.getOrNull(0)) { 'h' -> "Indian"; 'a' -> "American"; 'b' -> "British"; else -> "" }
                    val label = "${spec.raw.str("engine")?.replaceFirstChar { it.uppercase() } ?: spec.id} · $sp ($accent ${gender ?: ""})".replace("( ", "(").replace(" )", ")")
                    out += VoiceInfo("pack:${spec.id}#$sp", l, Tier.ON_DEVICE, label, gender, true, spec.raw.str("quality"), packs.isInstalled(spec.id))
                }
            }
        }
        if (backendUrl().isNotBlank()) out += hostedTts.voices(language)
        return out
    }

    /** Synthesizes speech following the voice policy unless options.tier is set. */
    fun synthesize(options: TtsOptions): TtsResult {
        if (!isEnabled(Feature.TTS)) throw VoiceException(ErrorCode.FEATURE_DISABLED, "TTS is disabled")
        var opts = options
        // "pack:<id>" and "os:<voice>" voice ids select the tier implicitly.
        opts.voiceId?.let { v ->
            if (v.startsWith("pack:")) {
                val body = v.removePrefix("pack:")
                opts = opts.copy(tier = Tier.ON_DEVICE, packId = body.substringBefore('#'), voiceId = body.substringAfter('#', "").ifBlank { null })
            }
            else if (v.startsWith("os:")) opts = opts.copy(tier = Tier.OS, voiceId = v.removePrefix("os:"))
            else if (v.startsWith("hosted:")) opts = opts.copy(tier = Tier.HOSTED, voiceId = v.removePrefix("hosted:"))
        }
        if (opts.voiceId == null && opts.tier == null) config.tts.preferredVoices[opts.language.code]?.let { pref ->
            if (pref.isNotBlank()) return synthesize(opts.copy(voiceId = pref))
        }
        val tiers = if (opts.tier != null) listOf(opts.tier!!) else ttsOrder()
        val reasons = ArrayList<String>()
        var last: VoiceException? = null
        for (t in tiers) {
            val (engine, av) = ttsEngine(t, opts.language, if (t == Tier.ON_DEVICE) opts.packId else null)
            if (engine == null || !av.available) { reasons += "tier${t.number}: ${av.reason}"; continue }
            try { return engine.synthesize(opts.copy(tier = t)) }
            catch (e: VoiceException) { last = e; reasons += "tier${t.number}: ${e.message}"; if (!config.tts.fallbackOnError && opts.tier == null) throw e }
            catch (e: Exception) { last = VoiceException(ErrorCode.ENGINE_FAILED, e.toString(), t, e); reasons += "tier${t.number}: $e" }
        }
        throw VoiceException(ErrorCode.NO_ENGINE, "No tier could synthesize (" + reasons.joinToString("; ") + ")", null, last)
    }

    /** Synthesize and play through the platform's audio sink. Returns once playback has started. */
    fun speak(options: TtsOptions, onDone: (() -> Unit)? = null): TtsResult {
        val r = synthesize(options)
        play(r.audio, onDone)
        return r
    }

    /** Plays PCM through the platform's (single) audio sink; a new play() stops the previous one. */
    fun play(audio: PcmAudio, onDone: (() -> Unit)? = null) {
        val sink = platform.audioSink() ?: throw VoiceException(ErrorCode.NO_ENGINE, "No audio output on ${platform.name}")
        sink.play(audio, onDone)
    }

    fun stopSpeaking() { platform.audioSink()?.stop() }

    /** Benchmark TTS tiers for one sentence. */
    fun benchmarkTts(options: TtsOptions, tiers: List<Tier> = Tier.entries.toList()): Map<String, Any?> {
        val rows = ArrayList<Map<String, Any?>>()
        for (t in tiers) {
            val row = linkedMapOf<String, Any?>("tier" to t.number, "label" to t.label)
            val (engine, av) = ttsEngine(t, options.language, if (t == Tier.ON_DEVICE) options.packId else null)
            row["available"] = av.available; row["reason"] = av.reason; row["engine"] = engine?.id
            if (engine != null && av.available) {
                try { val r = engine.synthesize(options.copy(tier = t)); row["ok"] = true; row["result"] = r.toJsonValue() }
                catch (e: VoiceException) { row["ok"] = false; row["error"] = e.toJsonValue() }
                catch (e: Exception) { row["ok"] = false; row["error"] = mapOf("message" to e.toString()) }
            } else row["ok"] = false
            rows += row
        }
        return linkedMapOf("text" to options.text, "language" to options.language.code, "tiers" to rows)
    }

    fun close() {
        worker.shutdown()
        loadedStt.values.forEach { it.close() }; loadedTts.values.forEach { it.close() }
        loadedStt.clear(); loadedTts.clear(); vad?.close(); vad = null
        lid?.close(); lid = null
        try { platform.close() } catch (e: Exception) { log.warn("platform close failed: ${e.message}") }
    }
}
