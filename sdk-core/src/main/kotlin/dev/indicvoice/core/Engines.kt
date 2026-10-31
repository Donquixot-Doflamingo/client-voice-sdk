package dev.indicvoice.core

import java.io.File

/** A speech-to-text engine of one tier. Implementations are thread-safe for sequential use. */
interface SttEngine {
    val id: String
    val tier: Tier
    fun availability(language: Language?): Availability
    fun transcribe(audio: PcmAudio, options: SttOptions): TranscriptResult
    fun close() {}
}

/** A text-to-speech engine of one tier. */
interface TtsEngine {
    val id: String
    val tier: Tier
    fun availability(language: Language): Availability
    fun voices(language: Language?): List<VoiceInfo>
    fun synthesize(options: TtsOptions): TtsResult
    fun close() {}
}

/**
 * Optional: tier-1 engines that can listen to the microphone themselves
 * (Android SpeechRecognizer, Apple Speech). The SDK prefers this path for
 * tier 1 because the OS engine does its own endpointing and partials.
 */
interface LiveCapable {
    fun startLive(options: SttOptions, listener: LiveSttListener): LiveSttSession
}

interface LiveSttSession {
    /** Stop listening; a final result (if any) is still delivered. */
    fun stop()
    /** Abort without a final result. */
    fun cancel()
    val tier: Tier
}

interface LiveSttListener {
    fun onReady() {}
    fun onSpeechStart() {}
    fun onSpeechEnd() {}
    fun onLevel(rmsDb: Double) {}
    fun onPartial(text: String, language: DetectedLanguage?) {}
    fun onFinal(result: TranscriptResult)
    fun onTierChanged(tier: Tier, reason: String) {}
    fun onError(error: VoiceException)
    fun onStopped() {}
}

/** Pulls 16 kHz mono frames from a microphone. Provided by the platform layer. */
interface AudioSource {
    val sampleRate: Int
    fun start(onFrame: (ShortArray) -> Unit)
    fun stop()
}

/** Plays 16-bit PCM. Provided by the platform layer. */
interface AudioSink {
    fun play(audio: PcmAudio, onDone: (() -> Unit)? = null)
    fun stop()
}

/** Creates tier-2 engines from installed packs (implemented by the sherpa-onnx module). */
interface OnDeviceEngineFactory {
    val runtimeName: String
    fun isRuntimeAvailable(): Availability
    fun stt(spec: PackSpec, dir: File, numThreads: Int): SttEngine
    fun tts(spec: PackSpec, dir: File, numThreads: Int): TtsEngine
    fun vad(spec: PackSpec, dir: File, threshold: Double): Vad
    /** Spoken language identification from audio (a multilingual Whisper pack). null = not supported by this runtime. */
    fun lid(spec: PackSpec, dir: File, numThreads: Int): LanguageIdentifier? = null
}

/**
 * Identifies the spoken language from the audio itself, before any transcription.
 * Auto mode uses it to pick the right model: text-based detection after the fact
 * cannot help when a model has already written English speech in Japanese or Devanagari.
 */
interface LanguageIdentifier : AutoCloseable {
    val id: String
    /** Language code as the model reports it ("en", "hi", "ja", ...), or "" when it cannot tell. */
    fun identify(audio: PcmAudio): String
    override fun close() {}
}

/** Frame-level voice activity detection at 16 kHz. */
interface Vad {
    /** Number of samples per frame this VAD expects. */
    val frameSize: Int
    fun isSpeech(frame: ShortArray): Boolean
    fun reset() {}
    fun close() {}
    val name: String
}

/** Dependency-free energy VAD, used when the silero pack is not installed. */
class EnergyVad(private val thresholdDb: Double = -42.0, override val frameSize: Int = 480) : Vad {
    override val name = "energy"
    private var noiseFloor = -60.0
    override fun isSpeech(frame: ShortArray): Boolean {
        var acc = 0.0
        for (s in frame) { val f = s / 32768.0; acc += f * f }
        val db = if (acc <= 0) -120.0 else 10 * Math.log10(acc / frame.size)
        // Adaptive floor: slowly track quiet frames.
        if (db < noiseFloor + 6) noiseFloor = noiseFloor * 0.98 + db * 0.02
        return db > maxOf(thresholdDb, noiseFloor + 12)
    }
    override fun reset() { noiseFloor = -60.0 }
}

/**
 * Turns a stream of frames into speech segments using a [Vad]. Used for live
 * recognition on tiers 2 and 3 (tier 1 engines do their own endpointing).
 */
class Segmenter(
    private val vad: Vad,
    private val sampleRate: Int,
    private val endpointSilenceMs: Int,
    private val maxUtteranceMs: Int,
    private val minSpeechMs: Int,
    private val paddingMs: Int = 200,
    private val onSpeechStart: () -> Unit,
    private val onSegment: (PcmAudio) -> Unit,
) {
    private val pending = ArrayList<Short>()
    private val buffer = ShortArrayBuilder()
    private val preRoll = ShortArrayBuilder()
    private var inSpeech = false
    private var silenceMs = 0
    private var speechMs = 0
    private val frameMs = vad.frameSize * 1000 / sampleRate
    private val maxPreRollSamples = sampleRate * paddingMs / 1000

    @Synchronized
    fun accept(samples: ShortArray) {
        for (s in samples) pending.add(s)
        while (pending.size >= vad.frameSize) {
            val frame = ShortArray(vad.frameSize) { pending[it] }
            pending.subList(0, vad.frameSize).clear()
            process(frame)
        }
    }

    private fun process(frame: ShortArray) {
        val speech = vad.isSpeech(frame)
        if (!inSpeech) {
            preRoll.append(frame)
            preRoll.trimToLast(maxPreRollSamples)
            if (speech) {
                inSpeech = true; silenceMs = 0; speechMs = frameMs
                buffer.clear(); buffer.append(preRoll.toArray())
                onSpeechStart()
            }
            return
        }
        buffer.append(frame)
        if (speech) { silenceMs = 0; speechMs += frameMs } else silenceMs += frameMs
        val totalMs = buffer.size * 1000 / sampleRate
        if (silenceMs >= endpointSilenceMs || totalMs >= maxUtteranceMs) {
            inSpeech = false
            val seg = buffer.toArray(); buffer.clear(); preRoll.clear()
            if (speechMs >= minSpeechMs) onSegment(PcmAudio(seg, sampleRate))
            speechMs = 0; silenceMs = 0
        }
    }

    /** Flush whatever is buffered (called on stop). */
    @Synchronized
    fun flush() {
        if (inSpeech && buffer.size > 0 && speechMs >= minSpeechMs) onSegment(PcmAudio(buffer.toArray(), sampleRate))
        inSpeech = false; buffer.clear(); preRoll.clear(); pending.clear(); speechMs = 0; silenceMs = 0
    }
}

class ShortArrayBuilder(initial: Int = 16000) {
    private var data = ShortArray(initial)
    var size = 0; private set
    fun append(a: ShortArray) {
        if (size + a.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + a.size))
        System.arraycopy(a, 0, data, size, a.size); size += a.size
    }
    fun trimToLast(n: Int) { if (size > n) { System.arraycopy(data, size - n, data, 0, n); size = n } }
    fun clear() { size = 0 }
    fun toArray(): ShortArray = data.copyOf(size)
}

/** Word/character error rates for benchmarking against a reference transcript. */
object Metrics {
    /**
     * Text normalisation before scoring, as usual for Indic ASR evaluation: lower case, no punctuation,
     * Unicode NFC, no zero-width joiners, nukta dropped and chandrabindu written as anusvara
     * (हूँ = हूं, ज़रूर = जरूर) so spelling variants of the same word are not counted as errors.
     */
    fun normalize(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
        .lowercase()
        .replace("\u200C", "").replace("\u200D", "")
        .replace("\u093C", "")            // nukta
        .replace('\u0901', '\u0902')      // chandrabindu → anusvara
        .replace(Regex("[\\p{Punct}।॥\"“”‘’]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun wer(reference: String, hypothesis: String): Double {
        val r = normalize(reference).split(' ').filter { it.isNotEmpty() }
        val h = normalize(hypothesis).split(' ').filter { it.isNotEmpty() }
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        return levenshtein(r, h).toDouble() / r.size
    }

    fun cer(reference: String, hypothesis: String): Double {
        val r = normalize(reference).replace(" ", "").map { it.toString() }
        val h = normalize(hypothesis).replace(" ", "").map { it.toString() }
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        return levenshtein(r, h).toDouble() / r.size
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        val prev = IntArray(b.size + 1) { it }
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.size]
    }
}
