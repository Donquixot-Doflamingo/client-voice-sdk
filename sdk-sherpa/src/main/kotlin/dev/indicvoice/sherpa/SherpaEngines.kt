package dev.indicvoice.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import dev.indicvoice.core.LanguageIdentifier
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad as SherpaVadNative
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.indicvoice.core.Availability
import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.OnDeviceEngineFactory
import dev.indicvoice.core.PackSpec
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.SttEngine
import dev.indicvoice.core.SttOptions
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TranscriptResult
import dev.indicvoice.core.TtsEngine
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.TtsResult
import dev.indicvoice.core.Vad
import dev.indicvoice.core.VoiceException
import dev.indicvoice.core.VoiceInfo
import dev.indicvoice.core.Word
import dev.indicvoice.core.obj
import dev.indicvoice.core.str
import java.io.File

/** Tier-2 factory backed by sherpa-onnx 1.13.x. Register it in the platform. */
class SherpaEngineFactory(private val log: Logger = Logger.NONE) : OnDeviceEngineFactory {
    override val runtimeName = "sherpa-onnx ${SherpaRuntime.VERSION}"
    override fun isRuntimeAvailable(): Availability = SherpaRuntime.availability()
    override fun stt(spec: PackSpec, dir: File, numThreads: Int): SttEngine = SherpaSttEngine(spec, dir, numThreads, log)
    override fun tts(spec: PackSpec, dir: File, numThreads: Int): TtsEngine = SherpaTtsEngine(spec, dir, numThreads, log)
    override fun vad(spec: PackSpec, dir: File, threshold: Double): Vad = SherpaVad(File(dir, spec.model.str("model") ?: "silero_vad.onnx"), threshold.toFloat())
    override fun lid(spec: PackSpec, dir: File, numThreads: Int): LanguageIdentifier? =
        if (spec.engine == "whisper") SherpaLanguageIdentifier(spec, dir, numThreads) else null
}

/** Spoken language identification with a multilingual Whisper pack (encoder + first decoder step only — fast). */
class SherpaLanguageIdentifier(spec: PackSpec, dir: File, numThreads: Int) : LanguageIdentifier {
    override val id = "sherpa-lid:${spec.id}"
    private val lock = Any()
    private var slid: SpokenLanguageIdentification?

    init {
        SherpaRuntime.prepareJvm().let { if (!it.available) throw VoiceException(ErrorCode.NO_ENGINE, it.reason ?: "runtime") }
        slid = SpokenLanguageIdentification(config = SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(encoder = spec.path(dir, "encoder"), decoder = spec.path(dir, "decoder")),
            numThreads = numThreads.coerceAtLeast(1), provider = "cpu"))
    }

    override fun identify(audio: PcmAudio): String = synchronized(lock) {
        val s = slid ?: throw VoiceException(ErrorCode.ENGINE_FAILED, "language identifier closed")
        val a = if (audio.sampleRate == 16000) audio else audio.resampled(16000)
        // Whisper looks at a 30 s window; the first ~10 s are plenty to tell the language.
        val samples = a.samples.let { if (it.size > 16000 * 10) it.copyOf(16000 * 10) else it }
        val stream = s.createStream()
        try {
            stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, 16000)
            s.compute(stream).trim().lowercase()
        } finally { stream.release() }
    }

    override fun close() = synchronized(lock) { slid?.release(); slid = null }
}

private fun PackSpec.path(dir: File, key: String): String {
    val v = model.str(key) ?: throw VoiceException(ErrorCode.CONFIG_ERROR, "pack '$id' has no model.$key")
    return File(dir, v).absolutePath
}

private fun PackSpec.optPath(dir: File, key: String): String {
    val v = model.str(key) ?: return ""
    return v.split(',').filter { it.isNotBlank() }.joinToString(",") { File(dir, it.trim()).absolutePath }
}

private const val WHISPER_PAD_SAMPLES = 16000 * 29

/** Non-streaming recognition over any sherpa-onnx offline model type the pack declares. */
class SherpaSttEngine(private val spec: PackSpec, dir: File, numThreads: Int, private val log: Logger) : SttEngine {
    override val id = "sherpa:${spec.id}"
    override val tier = Tier.ON_DEVICE
    private val lock = Any()
    private val isWhisper = spec.engine == "whisper"
    private var whisperLanguage = ""
    private val baseModelConfig: OfflineModelConfig
    @Volatile private var recognizer: OfflineRecognizer
    @Volatile private var closed = false

    init {
        SherpaRuntime.prepareJvm(log = log).let { if (!it.available) throw VoiceException(ErrorCode.NO_ENGINE, it.reason ?: "runtime") }
        baseModelConfig = when (spec.engine) {
            "dolphin-ctc" -> OfflineModelConfig(dolphin = OfflineDolphinModelConfig(model = spec.path(dir, "model")), tokens = spec.path(dir, "tokens"))
            "omnilingual-ctc" -> OfflineModelConfig(omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = spec.path(dir, "model")), tokens = spec.path(dir, "tokens"))
            "nemo-ctc" -> OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(model = spec.path(dir, "model")), tokens = spec.path(dir, "tokens"), modelType = "nemo_ctc")
            "zipformer-ctc" -> OfflineModelConfig(zipformerCtc = OfflineZipformerCtcModelConfig(model = spec.path(dir, "model")), tokens = spec.path(dir, "tokens"))
            "sense-voice" -> OfflineModelConfig(senseVoice = OfflineSenseVoiceModelConfig(model = spec.path(dir, "model"), language = "auto"), tokens = spec.path(dir, "tokens"))
            "transducer" -> OfflineModelConfig(transducer = OfflineTransducerModelConfig(encoder = spec.path(dir, "encoder"), decoder = spec.path(dir, "decoder"), joiner = spec.path(dir, "joiner")), tokens = spec.path(dir, "tokens"), modelType = "transducer")
            "whisper" -> OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = spec.path(dir, "encoder"), decoder = spec.path(dir, "decoder"), language = "", task = "transcribe"),
                tokens = WhisperBytes.byteSafeTokens(File(spec.path(dir, "tokens"))).absolutePath, modelType = "whisper")
            else -> throw VoiceException(ErrorCode.CONFIG_ERROR, "Unknown STT engine '${spec.engine}' in pack '${spec.id}'")
        }.also { it.numThreads = numThreads; it.provider = "cpu"; it.debug = false }
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(16000, 80), modelConfig = baseModelConfig, decodingMethod = "greedy_search"))
    }

    override fun availability(language: Language?): Availability =
        if (spec.supports(language)) Availability(true, null, mapOf("packId" to spec.id)) else Availability.no("pack '${spec.id}' does not cover ${language?.code}")

    override fun transcribe(audio: PcmAudio, options: SttOptions): TranscriptResult {
        val pcm = audio.resampled(16000)
        if (pcm.samples.isEmpty()) throw VoiceException(ErrorCode.BAD_AUDIO, "Empty audio", tier)
        val t0 = System.currentTimeMillis()
        val result = synchronized(lock) {
            if (closed) throw VoiceException(ErrorCode.ENGINE_FAILED, "$id was closed", tier)
            if (isWhisper) {
                // Whisper can be forced to a language or left to detect it; switching requires a new recognizer.
                val want = if (options.language != null && options.language in spec.languages) options.language!!.code else ""
                if (want != whisperLanguage) {
                    recognizer.release()
                    baseModelConfig.whisper.language = want
                    recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(featConfig = FeatureConfig(16000, 80), modelConfig = baseModelConfig, decodingMethod = "greedy_search"))
                    whisperLanguage = want
                }
            }
            val stream = recognizer.createStream()
            try {
                // sherpa-onnx caps Whisper output at ~6 tokens per second of input. Indic scripts need 3-4x more
                // byte-level tokens, so Hindi was cut off mid-sentence. Trailing silence raises the cap; we add
                // just enough (4x the speech, max 29 s) for non-English, because encoder time grows with length.
                val padTo = if (isWhisper && whisperLanguage != "en" && spec.languages != listOf(Language.ENGLISH)) minOf(WHISPER_PAD_SAMPLES, pcm.samples.size * 4 + 16000) else 0
                val samples = if (pcm.samples.size < padTo) pcm.toFloatArray().copyOf(padTo) else pcm.toFloatArray()
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                recognizer.getResult(stream)
            } finally { stream.release() }
        }
        val latency = System.currentTimeMillis() - t0
        val text = if (isWhisper) WhisperBytes.restore(result.text) else result.text
        val words = if (isWhisper) null else wordsFromTokens(result.tokens, result.timestamps, text)
        return TranscriptResult.fromText(
            text = text, tier = tier, engine = id, latencyMs = latency, audioMs = pcm.durationMs,
            engineLang = result.lang.ifBlank { null }, hint = options.language, allowed = options.allowedLanguages,
            words = words, raw = mapOf("tokens" to result.tokens.size, "modelLang" to result.lang),
        )
    }

    /** Groups sub-word tokens (sentencepiece "▁" / leading space) into words with timestamps when available. */
    private fun wordsFromTokens(tokens: Array<String>, timestamps: FloatArray, text: String): List<Word>? {
        if (tokens.isEmpty() || timestamps.size != tokens.size) return null
        val words = ArrayList<Word>()
        var cur = StringBuilder(); var start = 0f; var last = 0f
        fun flush(end: Float) {
            val w = cur.toString().trim().trim('।', '॥', ',', '.', '!', '?', ';', ':')
            if (w.isNotEmpty()) words += Word(w, (start * 1000).toInt(), (end * 1000).toInt())
            cur = StringBuilder()
        }
        for (i in tokens.indices) {
            val tok = tokens[i]
            val startsWord = tok.startsWith("▁") || tok.startsWith(" ")
            val clean = tok.removePrefix("▁").trimStart()
            if (startsWord && cur.isNotEmpty()) flush(timestamps[i])
            if (cur.isEmpty()) start = timestamps[i]
            cur.append(clean)
            last = timestamps[i]
        }
        flush(last + 0.2f)
        // Only trust this segmentation when it agrees with the whitespace split; otherwise fall back.
        val plain = TranscriptResult.splitWords(text)
        return if (words.size == plain.size) words else null
    }

    override fun close() { synchronized(lock) { if (!closed) { closed = true; recognizer.release() } } }
}

/**
 * Work-around for sherpa-onnx 1.13.x Whisper decoding in non-Latin scripts.
 *
 * Whisper's tokens are byte-level BPE: one Devanagari letter (3 UTF-8 bytes) is often split across two tokens.
 * sherpa-onnx 1.13 turns each token into text on its own and drops the incomplete bytes, so "नाम" comes out as
 * "ाम" (fixed upstream in 2.0, which we cannot use yet because 2.0 removed the espeak-ng front end our TTS needs).
 * We give sherpa a rewritten tokens file in which every incomplete byte is a private-use character
 * (U+F700 + byte) and turn those back into bytes afterwards, so the original UTF-8 is reassembled exactly.
 */
object WhisperBytes {
    private const val BASE = 0xF700

    /** Returns `<tokens>.bytesafe.txt` next to the original (created once), or the original if it cannot be written. */
    fun byteSafeTokens(tokens: File): File {
        val out = File(tokens.parentFile, tokens.name.removeSuffix(".txt") + ".bytesafe.txt")
        if (out.exists() && out.lastModified() >= tokens.lastModified() && out.length() > 0) return out
        return try {
            val dec = Charsets.UTF_8.newDecoder()
            val sb = StringBuilder()
            tokens.forEachLine { line ->
                val cut = line.lastIndexOf(' ')
                if (cut <= 0) { sb.append(line).append('\n'); return@forEachLine }
                // Some special entries are not base64 (e.g. "= 50256"): keep those lines unchanged.
                val bytes = try { java.util.Base64.getDecoder().decode(line.substring(0, cut)) } catch (e: IllegalArgumentException) { sb.append(line).append('\n'); return@forEachLine }
                val safe = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < bytes.size) {
                    var took = 0
                    for (len in 4 downTo 1) {
                        if (i + len > bytes.size) continue
                        val ok = try { dec.reset(); dec.decode(java.nio.ByteBuffer.wrap(bytes, i, len)); true } catch (e: java.nio.charset.CharacterCodingException) { false }
                        if (ok) { safe.write(bytes, i, len); took = len; break }
                    }
                    if (took == 0) { safe.write(String(Character.toChars(BASE + (bytes[i].toInt() and 0xFF))).toByteArray(Charsets.UTF_8)); took = 1 }
                    i += took
                }
                sb.append(java.util.Base64.getEncoder().encodeToString(safe.toByteArray())).append(line.substring(cut)).append('\n')
            }
            out.writeText(sb.toString())
            out
        } catch (e: Exception) { tokens }
    }

    /** Turns private-use byte markers back into bytes and decodes the whole string as UTF-8. */
    fun restore(text: String): String {
        if (text.none { it.code in BASE..BASE + 0xFF }) return text
        val bytes = java.io.ByteArrayOutputStream()
        for (ch in text) {
            if (ch.code in BASE..BASE + 0xFF) bytes.write(ch.code - BASE) else bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).replace("\uFFFD", "")
    }
}

/** Offline TTS over VITS (Piper / Indic-TTS exports), Kokoro or Matcha packs. */
class SherpaTtsEngine(private val spec: PackSpec, dir: File, numThreads: Int, private val log: Logger) : TtsEngine {
    override val id = "sherpa:${spec.id}"
    override val tier = Tier.ON_DEVICE
    private val lock = Any()
    private val tts: OfflineTts
    @Volatile private var closed = false
    private val sampleRate: Int
    private val voices: Map<String, Map<String, Int>> // language -> (voice name -> sid)

    init {
        SherpaRuntime.prepareJvm(log = log).let { if (!it.available) throw VoiceException(ErrorCode.NO_ENGINE, it.reason ?: "runtime") }
        val model = when (spec.engine) {
            "vits" -> OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                model = spec.path(dir, "model"), tokens = spec.path(dir, "tokens"), lexicon = spec.optPath(dir, "lexicon"),
                dataDir = spec.optPath(dir, "dataDir"), dictDir = spec.optPath(dir, "dictDir")))
            "kokoro" -> OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                model = spec.path(dir, "model"), voices = spec.path(dir, "voices"), tokens = spec.path(dir, "tokens"),
                dataDir = spec.optPath(dir, "dataDir"), lexicon = spec.optPath(dir, "lexicon"), dictDir = spec.optPath(dir, "dictDir")))
            "matcha" -> OfflineTtsModelConfig(matcha = OfflineTtsMatchaModelConfig(
                acousticModel = spec.path(dir, "acousticModel"), vocoder = spec.path(dir, "vocoder"), tokens = spec.path(dir, "tokens"),
                lexicon = spec.optPath(dir, "lexicon"), dataDir = spec.optPath(dir, "dataDir"), dictDir = spec.optPath(dir, "dictDir")))
            else -> throw VoiceException(ErrorCode.CONFIG_ERROR, "Unknown TTS engine '${spec.engine}' in pack '${spec.id}'")
        }.also { it.numThreads = numThreads; it.provider = "cpu" }
        tts = OfflineTts(config = OfflineTtsConfig(model = model, ruleFsts = spec.optPath(dir, "ruleFsts"), maxNumSentences = 1))
        sampleRate = tts.sampleRate()
        @Suppress("UNCHECKED_CAST")
        voices = (spec.raw["voices"] as? Map<String, Any?>)?.mapValues { (_, v) ->
            (v as? Map<String, Any?>)?.mapValues { (_, sid) -> (sid as? Number)?.toInt() ?: 0 } ?: emptyMap()
        } ?: emptyMap()
    }

    override fun availability(language: Language): Availability =
        if (spec.supports(language)) Availability(true, null, mapOf("packId" to spec.id, "sampleRate" to sampleRate)) else Availability.no("pack '${spec.id}' has no ${language.code} voice")

    override fun voices(language: Language?): List<VoiceInfo> {
        val langs = if (language != null) listOf(language) else spec.languages
        return langs.filter { spec.supports(it) }.flatMap { l ->
            val named = voices[l.code]
            if (named.isNullOrEmpty()) listOf(VoiceInfo("pack:${spec.id}", l, tier, spec.name, spec.raw.str("gender"), true, spec.raw.str("quality")))
            else named.keys.map { n -> VoiceInfo("pack:${spec.id}#$n", l, tier, "${spec.name} / $n", if (n.getOrNull(1) == 'f') "female" else if (n.getOrNull(1) == 'm') "male" else null, true, spec.raw.str("quality")) }
        }
    }

    override fun synthesize(options: TtsOptions): TtsResult {
        if (!spec.supports(options.language)) throw VoiceException(ErrorCode.LANGUAGE_UNSUPPORTED, "pack '${spec.id}' has no ${options.language.code} voice", tier)
        val text = options.text.trim()
        if (text.isEmpty()) throw VoiceException(ErrorCode.BAD_AUDIO, "Empty text", tier)
        // Resolve the speaker id: "pack:<id>#<voice>" or a bare voice name, else the first voice for the language.
        val voiceName = options.voiceId?.substringAfter('#', options.voiceId!!)?.takeIf { it.isNotBlank() && !it.startsWith("pack:") }
        val langVoices = voices[options.language.code] ?: emptyMap()
        val sid = voiceName?.let { langVoices[it] ?: voices.values.firstNotNullOfOrNull { m -> m[it] } } ?: langVoices.values.firstOrNull() ?: 0
        val gen = GenerationConfig(speed = options.speed.toFloat(), sid = sid)
        if (spec.engine == "kokoro" && options.language != Language.ENGLISH) gen.extra = mapOf("lang" to espeakVoice(options.language))
        val t0 = System.currentTimeMillis()
        val audio = synchronized(lock) {
            if (closed) throw VoiceException(ErrorCode.ENGINE_FAILED, "$id was closed", tier)
            tts.generateWithConfig(text, gen)
        }
        val latency = System.currentTimeMillis() - t0
        if (audio.samples.isEmpty()) throw VoiceException(ErrorCode.ENGINE_FAILED, "TTS produced no audio (text may contain unsupported characters)", tier)
        var pcm = PcmAudio.fromFloat(audio.samples, audio.sampleRate)
        if (options.sampleRate != null && options.sampleRate != pcm.sampleRate) pcm = pcm.resampled(options.sampleRate)
        val voiceId = "pack:${spec.id}" + (voiceName?.let { "#$it" } ?: langVoices.entries.firstOrNull { it.value == sid }?.let { "#${it.key}" } ?: "")
        return TtsResult(pcm, options.language, voiceId, tier, id, latency)
    }

    private fun espeakVoice(l: Language): String = when (l) {
        Language.HINDI -> "hi"; Language.BENGALI -> "bn"; Language.TAMIL -> "ta"; Language.TELUGU -> "te"; Language.MARATHI -> "mr"
        Language.GUJARATI -> "gu"; Language.KANNADA -> "kn"; Language.MALAYALAM -> "ml"; Language.PUNJABI -> "pa"; Language.URDU -> "ur"
        Language.NEPALI -> "ne"; Language.ASSAMESE -> "as"; Language.ODIA -> "or"; Language.SINDHI -> "sd"; Language.KONKANI -> "kok"
        Language.SANSKRIT -> "hi"; Language.MAITHILI -> "hi"; Language.DOGRI -> "hi"; Language.BODO -> "hi"; Language.KASHMIRI -> "ur"
        Language.MANIPURI -> "bn"; Language.SANTALI -> "hi"; Language.ENGLISH -> "en-us"
    }

    override fun close() { synchronized(lock) { if (!closed) { closed = true; tts.release() } } }
}

/** Silero VAD through sherpa-onnx (frame = 512 samples at 16 kHz). */
class SherpaVad(modelFile: File, private val threshold: Float) : Vad {
    override val name = "silero"
    override val frameSize = 512
    private val vad: SherpaVadNative
    init {
        SherpaRuntime.prepareJvm().let { if (!it.available) throw VoiceException(ErrorCode.NO_ENGINE, it.reason ?: "runtime") }
        vad = SherpaVadNative(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = modelFile.absolutePath, threshold = threshold, windowSize = 512), sampleRate = 16000, numThreads = 1))
    }
    override fun isSpeech(frame: ShortArray): Boolean {
        val f = FloatArray(frame.size) { frame[it] / 32768f }
        return vad.compute(f) >= threshold
    }
    override fun reset() { vad.reset() }
    override fun close() { vad.release() }
}
