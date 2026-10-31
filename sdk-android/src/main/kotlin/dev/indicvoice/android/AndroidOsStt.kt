package dev.indicvoice.android

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.indicvoice.core.Availability
import dev.indicvoice.core.DetectedLanguage
import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.Language
import dev.indicvoice.core.LanguageDetector
import dev.indicvoice.core.LiveCapable
import dev.indicvoice.core.LiveSttListener
import dev.indicvoice.core.LiveSttSession
import dev.indicvoice.core.Logger
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.SttConfig
import dev.indicvoice.core.SttEngine
import dev.indicvoice.core.SttOptions
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TranscriptResult
import dev.indicvoice.core.VoiceException
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier 1 on Android: the platform SpeechRecognizer (Google's recogniser on
 * most phones; on-device models on Android 12+ where installed).
 *
 * Live mode is the primary path (the OS does endpointing + partials).
 * Batch mode (transcribing an existing recording, needed for benchmarking)
 * is only possible on Android 13+ through EXTRA_AUDIO_SOURCE.
 */
class AndroidOsSttEngine(
    private val context: Context,
    private val cfg: SttConfig,
    private val allowedLanguages: List<Language>,
    private val log: Logger,
) : SttEngine, LiveCapable {
    override val id = "os:android-speechrecognizer"
    override val tier = Tier.OS
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var support: RecognitionSupport? = null
    @Volatile private var supportError: String? = null

    init { refreshSupport() }

    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Android 13+: ask the recogniser which languages are installed / downloadable on-device. */
    fun refreshSupport(language: Language? = null) {
        if (Build.VERSION.SDK_INT < 33) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        main.post {
            try {
                val rec = if (onDeviceAvailable()) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
                rec.checkRecognitionSupport(baseIntent(language), executor, object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) { support = s; supportError = null; main.post { rec.destroy() } }
                    override fun onError(error: Int) { supportError = "checkRecognitionSupport error $error"; main.post { rec.destroy() } }
                })
            } catch (e: Exception) { supportError = e.toString() }
        }
    }

    /** Android 13+: download the OS on-device model for a language. */
    fun downloadLanguage(language: Language, onProgress: (Int) -> Unit = {}, onDone: (Boolean, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 34) { onDone(false, "OS model download needs Android 14+"); return }
        main.post {
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            rec.triggerModelDownload(baseIntent(language), executor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
                override fun onSuccess() { onDone(true, null); main.post { rec.destroy() } }
                override fun onScheduled() { onDone(true, "scheduled (will download on Wi-Fi)"); main.post { rec.destroy() } }
                override fun onError(error: Int) { onDone(false, "download error $error"); main.post { rec.destroy() } }
            })
        }
    }

    override fun close() { executor.shutdown() }

    override fun availability(language: Language?): Availability {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return Availability.no("no speech recognition service on this device (Google app missing?)")
        val details = LinkedHashMap<String, Any?>()
        details["onDeviceRecognizer"] = onDeviceAvailable()
        details["sdk"] = Build.VERSION.SDK_INT
        support?.let { s ->
            details["installedOnDevice"] = s.installedOnDeviceLanguages
            details["downloadableOnDevice"] = s.supportedOnDeviceLanguages
            details["online"] = s.onlineLanguages
            if (language != null) {
                val tag = language.bcp47
                val installed = s.installedOnDeviceLanguages.any { it.equals(tag, true) }
                val downloadable = s.supportedOnDeviceLanguages.any { it.equals(tag, true) }
                val online = s.onlineLanguages.any { it.equals(tag, true) }
                details["languageInstalled"] = installed; details["languageDownloadable"] = downloadable; details["languageOnline"] = online
                if (!installed && !online && !downloadable) return Availability.no("OS recogniser does not support ${language.englishName}", details)
                if (!installed && !online && !cfg.tier1AllowOnline) return Availability.no("${language.englishName} model not installed on device", details)
            }
        }
        details["batchTranscription"] = Build.VERSION.SDK_INT >= 33
        return Availability(true, null, details)
    }

    private fun baseIntent(language: Language?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, (language ?: Language.HINDI).bcp47)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, (language ?: Language.HINDI).bcp47)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (Build.VERSION.SDK_INT >= 23) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, cfg.tier1PreferOnDevice)
        if (Build.VERSION.SDK_INT >= 34) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            val allowed = ArrayList(allowedLanguages.map { it.bcp47 })
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
            if (language == null) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (onDeviceAvailable() && cfg.tier1PreferOnDevice && !cfg.tier1AllowOnline) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    // ------------------------------------------------------------ batch

    override fun transcribe(audio: PcmAudio, options: SttOptions): TranscriptResult {
        if (Build.VERSION.SDK_INT < 33) throw VoiceException(ErrorCode.NO_ENGINE, "Android ${Build.VERSION.SDK_INT}: the OS recogniser can only listen to the microphone (batch needs Android 13+)", tier)
        if (Looper.myLooper() == Looper.getMainLooper()) throw VoiceException(ErrorCode.ENGINE_FAILED, "transcribe() must not be called on the main thread", tier)
        val pcm = audio.resampled(16000)
        val pipe = ParcelFileDescriptor.createPipe()
        val intent = baseIntent(options.language).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        }
        val latch = CountDownLatch(1)
        var result: TranscriptResult? = null
        var error: VoiceException? = null
        val t0 = System.currentTimeMillis()
        var recognizer: SpeechRecognizer? = null
        main.post {
            try {
                val rec = createRecognizer(); recognizer = rec
                rec.setRecognitionListener(ResultListener(options, pcm.durationMs, t0, object : LiveSttListener {
                    override fun onFinal(r: TranscriptResult) { result = r; latch.countDown() }
                    override fun onError(e: VoiceException) { error = e; latch.countDown() }
                }))
                rec.startListening(intent)
            } catch (e: Exception) { error = VoiceException(ErrorCode.ENGINE_FAILED, e.toString(), tier, e); latch.countDown() }
        }
        // Feed the recording through the pipe (plus a little silence so the endpointer fires).
        // Daemon thread: if the recogniser never drains the pipe, the blocked write dies with the process, not as a leak.
        Thread({
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { out ->
                    out.write(pcm.toByteArrayLE()); out.write(PcmAudio.silence(1200, 16000).toByteArrayLE())
                }
            } catch (_: Exception) {} finally { try { pipe[1].close() } catch (_: Exception) {} }
        }, "indicvoice-pipe").apply { isDaemon = true; start() }
        latch.await(maxOf(15000L, pcm.durationMs * 3), TimeUnit.MILLISECONDS)
        main.post { try { recognizer?.destroy() } catch (_: Exception) {} }
        try { pipe[0].close() } catch (_: Exception) {}
        error?.let { throw it }
        return result ?: throw VoiceException(ErrorCode.TIMEOUT, "OS recogniser did not answer", tier)
    }

    // ------------------------------------------------------------ live

    override fun startLive(options: SttOptions, listener: LiveSttListener): LiveSttSession {
        val session = LiveSession(options, listener)
        main.post { session.begin() }
        return session
    }

    private inner class LiveSession(private val options: SttOptions, private val listener: LiveSttListener) : LiveSttSession {
        override val tier = Tier.OS
        private var recognizer: SpeechRecognizer? = null
        private val done = AtomicBoolean(false)
        private var startedAt = 0L

        fun begin() {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) { listener.onError(VoiceException(ErrorCode.NO_ENGINE, "no speech recognition service", tier)); finish(); return }
                val rec = createRecognizer(); recognizer = rec
                startedAt = System.currentTimeMillis()
                rec.setRecognitionListener(ResultListener(options, 0, startedAt, object : LiveSttListener {
                    override fun onReady() { if (!done.get()) listener.onReady() }
                    override fun onSpeechStart() { if (!done.get()) listener.onSpeechStart() }
                    override fun onSpeechEnd() { if (!done.get()) listener.onSpeechEnd() }
                    override fun onLevel(rmsDb: Double) { if (!done.get()) listener.onLevel(rmsDb) }
                    override fun onPartial(text: String, language: DetectedLanguage?) { if (!done.get()) listener.onPartial(text, language) }
                    // Android may deliver onError after onResults; forward each terminal callback once.
                    override fun onFinal(result: TranscriptResult) { if (done.get()) return; listener.onFinal(result); finish() }
                    override fun onError(error: VoiceException) { if (done.get()) return; listener.onError(error); finish() }
                }))
                listener.onTierChanged(tier, "OS recogniser" + if (onDeviceAvailable()) " (on-device capable)" else "")
                rec.startListening(baseIntent(options.language))
            } catch (e: Exception) { listener.onError(VoiceException(ErrorCode.ENGINE_FAILED, e.toString(), tier, e)); finish() }
        }

        private fun finish() {
            if (!done.compareAndSet(false, true)) return
            main.post { try { recognizer?.destroy() } catch (_: Exception) {}; listener.onStopped() }
        }

        override fun stop() { main.post { try { recognizer?.stopListening() } catch (_: Exception) {} } }
        override fun cancel() { main.post { try { recognizer?.cancel() } catch (_: Exception) {} }; finish() }
    }

    /** Maps Android callbacks to the SDK's listener and output format. */
    private inner class ResultListener(private val options: SttOptions, private val audioMs: Long, private val t0: Long, private val out: LiveSttListener) : RecognitionListener {
        private var detectedLang: String? = null
        private var speechStartedAt = 0L

        override fun onReadyForSpeech(params: Bundle?) = out.onReady()
        override fun onBeginningOfSpeech() { speechStartedAt = System.currentTimeMillis(); out.onSpeechStart() }
        override fun onRmsChanged(rmsdB: Float) = out.onLevel(rmsdB.toDouble() - 30)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = out.onSpeechEnd()
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT >= 34) {
                val lang = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                val conf = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN)
                if (lang != null && conf >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) detectedLang = lang
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (text.isNotBlank()) out.onPartial(text, null)
        }
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val text = list.firstOrNull() ?: ""
            val now = System.currentTimeMillis()
            val latency = if (speechStartedAt > 0) now - speechStartedAt else now - t0
            out.onFinal(TranscriptResult.fromText(
                text = text, tier = Tier.OS, engine = id, latencyMs = latency, audioMs = if (audioMs > 0) audioMs else (if (speechStartedAt > 0) now - speechStartedAt else 0),
                engineLang = detectedLang ?: options.language?.bcp47, hint = options.language, allowed = options.allowedLanguages,
                confidence = scores?.firstOrNull()?.toDouble(), raw = mapOf("alternatives" to list.drop(1), "detectedLanguage" to detectedLang),
            ))
        }
        override fun onError(error: Int) {
            val (code, msg) = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorCode.BAD_AUDIO to "no speech recognised"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorCode.PERMISSION_DENIED to "RECORD_AUDIO permission missing"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ErrorCode.NETWORK to "OS recogniser needs network and failed (error $error)"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ErrorCode.LANGUAGE_UNSUPPORTED to "OS recogniser does not have this language (error $error)"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ErrorCode.ENGINE_FAILED to "OS recogniser busy"
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> ErrorCode.ENGINE_FAILED to "too many requests to OS recogniser"
                else -> ErrorCode.ENGINE_FAILED to "OS recogniser error $error"
            }
            out.onError(VoiceException(code, msg, Tier.OS))
        }
    }
}
