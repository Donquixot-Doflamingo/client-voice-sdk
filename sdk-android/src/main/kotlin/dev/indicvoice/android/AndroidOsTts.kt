package dev.indicvoice.android

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.indicvoice.core.Availability
import dev.indicvoice.core.ErrorCode
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TtsConfig
import dev.indicvoice.core.TtsEngine
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.TtsResult
import dev.indicvoice.core.VoiceException
import dev.indicvoice.core.VoiceInfo
import dev.indicvoice.core.Wav
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tier 1 TTS on Android: the platform TextToSpeech service (Google's engine
 * ships offline voices for 20 of the 22 scheduled languages). We synthesise
 * to a WAV file and hand PCM back so playback, barge-in and benchmarking work
 * the same way for every tier.
 */
class AndroidOsTtsEngine(private val context: Context, private val cfg: TtsConfig, private val log: Logger) : TtsEngine {
    override val id = "os:android-tts"
    override val tier = Tier.OS
    private val ready = CountDownLatch(1)
    @Volatile private var initStatus = -1
    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val failures = ConcurrentHashMap<String, String>()
    private val tts: TextToSpeech = TextToSpeech(context) { status -> initStatus = status; ready.countDown() }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.countDown() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { utteranceId?.let { failures[it] = "engine error"; pending.remove(it)?.countDown() } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { failures[it] = "engine error $errorCode"; pending.remove(it)?.countDown() } }
        })
    }

    private fun awaitInit(): Boolean { ready.await(5, TimeUnit.SECONDS); return initStatus == TextToSpeech.SUCCESS }

    private fun locale(l: Language): Locale = Locale.forLanguageTag(l.bcp47)

    override fun availability(language: Language): Availability {
        if (!awaitInit()) return Availability.no("TextToSpeech engine failed to initialise (no TTS engine installed?)")
        val code = try { tts.isLanguageAvailable(locale(language)) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        if (code < TextToSpeech.LANG_AVAILABLE) return Availability.no("OS TTS has no ${language.englishName} voice (install it in Settings → Text-to-speech)", mapOf("engine" to tts.defaultEngine))
        val voices = voicesFor(language)
        val offline = voices.filter { !it.isNetworkConnectionRequired }
        if (offline.isEmpty() && cfg.tier1PreferOffline && voices.isNotEmpty()) {
            return Availability(true, "only online voices installed for ${language.englishName}", mapOf("engine" to tts.defaultEngine, "voices" to voices.map { it.name }))
        }
        return Availability(true, null, mapOf("engine" to tts.defaultEngine, "voices" to voices.map { it.name }, "offlineVoices" to offline.size))
    }

    private fun voicesFor(language: Language): List<Voice> = try {
        val target = locale(language)
        (tts.voices ?: emptySet()).filter { it.locale.language == target.language }.sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
    } catch (e: Exception) { emptyList() }

    override fun voices(language: Language?): List<VoiceInfo> {
        if (!awaitInit()) return emptyList()
        val langs = if (language != null) listOf(language) else Language.entries
        return langs.flatMap { l -> voicesFor(l).map { v -> VoiceInfo("os:${v.name}", l, tier, v.name, null, !v.isNetworkConnectionRequired, qualityLabel(v.quality)) } }
    }

    private fun qualityLabel(q: Int) = when { q >= Voice.QUALITY_VERY_HIGH -> "very-high"; q >= Voice.QUALITY_HIGH -> "high"; q >= Voice.QUALITY_NORMAL -> "normal"; else -> "low" }

    override fun synthesize(options: TtsOptions): TtsResult {
        if (!awaitInit()) throw VoiceException(ErrorCode.NO_ENGINE, "TextToSpeech not initialised", tier)
        val l = options.language
        val candidates = voicesFor(l)
        val wanted = options.voiceId?.removePrefix("os:")
        val voice = candidates.firstOrNull { it.name == wanted } ?: candidates.firstOrNull { !it.isNetworkConnectionRequired } ?: candidates.firstOrNull()
        synchronized(tts) {
            if (voice != null) tts.voice = voice else if (tts.setLanguage(locale(l)) < TextToSpeech.LANG_AVAILABLE) throw VoiceException(ErrorCode.LANGUAGE_UNSUPPORTED, "OS TTS has no ${l.englishName}", tier)
            tts.setSpeechRate(options.speed.toFloat())
        }
        val utteranceId = "iv-" + System.nanoTime()
        val file = File(context.cacheDir, "$utteranceId.wav")
        val latch = CountDownLatch(1)
        pending[utteranceId] = latch
        val t0 = System.currentTimeMillis()
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        val rc = tts.synthesizeToFile(options.text, params, file, utteranceId)
        if (rc != TextToSpeech.SUCCESS) { pending.remove(utteranceId); throw VoiceException(ErrorCode.ENGINE_FAILED, "synthesizeToFile returned $rc", tier) }
        if (!latch.await(30, TimeUnit.SECONDS)) { pending.remove(utteranceId); throw VoiceException(ErrorCode.TIMEOUT, "OS TTS timed out", tier) }
        failures.remove(utteranceId)?.let { file.delete(); throw VoiceException(ErrorCode.ENGINE_FAILED, "OS TTS: $it", tier) }
        val latency = System.currentTimeMillis() - t0
        val audio = try { Wav.read(file) } finally { file.delete() }
        val targetRate = options.sampleRate
        val pcm = if (targetRate != null && targetRate != audio.sampleRate) audio.resampled(targetRate) else audio
        return TtsResult(pcm, l, "os:" + (voice?.name ?: l.bcp47), tier, id + ":" + tts.defaultEngine, latency)
    }

    override fun close() { try { tts.shutdown() } catch (_: Exception) {} }
}
