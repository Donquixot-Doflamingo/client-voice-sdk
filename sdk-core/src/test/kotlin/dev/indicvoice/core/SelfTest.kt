package dev.indicvoice.core

import java.io.File

/**
 * Dependency-free self test of the core (run by scripts/run-tests.sh):
 *   java -cp build/selftest.jar:build/indicvoice-cli.jar dev.indicvoice.core.SelfTest [wav-file]
 * Prints one line per check; exits non-zero on failure.
 */
object SelfTest {
    private var failures = 0
    private fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  — $detail" else "")
        if (!ok) failures++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // ---- JSON
        val json = """{"a":1,"b":[true,null,"xन"],"c":{"d":-2.5e2,"e":"q\"uote"}}"""
        val parsed = Json.parseObject(json)
        check("json parse", parsed.int("a", 0) == 1 && parsed.list("b")?.size == 3 && parsed.obj("c")?.double("d", 0.0) == -250.0 && parsed.obj("c")?.str("e") == "q\"uote")
        check("json round trip", Json.stringify(Json.parseObject(Json.stringify(parsed))) == Json.stringify(parsed))

        // ---- Language table + detection
        check("23 languages", Language.entries.size == 23, Language.entries.size.toString())
        check("parse hi-IN", Language.parse("hi-IN") == Language.HINDI && Language.parse("HINDI") == Language.HINDI && Language.parse("auto") == null)
        val samples = listOf(
            Language.HINDI to "मुझे एक गिलास पानी चाहिए और कल सुबह दिल्ली जाना है",
            Language.MARATHI to "मला एक ग्लास पाणी पाहिजे आणि उद्या सकाळी दिल्लीला जायचे आहे",
            Language.NEPALI to "मलाई एक गिलास पानी चाहिन्छ र भोलि बिहान दिल्ली जानु छ",
            Language.BENGALI to "আমার এক গ্লাস জল চাই এবং কাল সকালে দিল্লি যেতে হবে",
            Language.ASSAMESE to "মোক এগিলাছ পানী লাগে আৰু কাইলৈ ৰাতিপুৱা দিল্লীলৈ যাব লাগিব",
            Language.TAMIL to "எனக்கு ஒரு கிளாஸ் தண்ணீர் வேண்டும்",
            Language.TELUGU to "నాకు ఒక గ్లాసు నీళ్లు కావాలి",
            Language.KANNADA to "ನನಗೆ ಒಂದು ಲೋಟ ನೀರು ಬೇಕು",
            Language.MALAYALAM to "എനിക്ക് ഒരു ഗ്ലാസ് വെള്ളം വേണം",
            Language.GUJARATI to "મને એક ગ્લાસ પાણી જોઈએ છે",
            Language.PUNJABI to "ਮੈਨੂੰ ਇੱਕ ਗਲਾਸ ਪਾਣੀ ਚਾਹੀਦਾ ਹੈ",
            Language.ODIA to "ମୋତେ ଗୋଟିଏ ଗ୍ଲାସ ପାଣି ଦରକାର",
            Language.URDU to "مجھے ایک گلاس پانی چاہیے اور کل صبح دلی جانا ہے",
            Language.ENGLISH to "I need a glass of water and have to go to Delhi tomorrow morning",
            Language.SANTALI to "ᱤᱧ ᱫᱟᱜ ᱞᱟᱹᱠᱛᱤ",
        )
        for ((lang, text) in samples) {
            val d = LanguageDetector.detect(text)
            check("detect ${lang.code}", d.language == lang, "got ${d.language.code}/${d.script.iso15924} via ${d.source} (${"%.2f".format(d.confidence)})")
        }
        val hinglish = LanguageDetector.detect("mujhe ek glass paani chahiye aur kal subah delhi jana hai")
        check("detect romanised Hindi", hinglish.language == Language.HINDI && hinglish.script == Script.LATIN, "got ${hinglish.language.code}/${hinglish.script.iso15924}")
        val engineWins = LanguageDetector.detect("Namaste mera naam Mayank hai", engineLang = "hi")
        check("engine language respected", engineWins.language == Language.HINDI && engineWins.source == "engine")
        val hinted = LanguageDetector.detect("नमस्ते", hint = Language.MARATHI)
        check("hint breaks shared-script tie", hinted.language == Language.MARATHI, "got ${hinted.language.code} via ${hinted.source}")

        // ---- Transcript format
        val tr = TranscriptResult.fromText("नमस्ते मेरा नाम मयंक है।", Tier.ON_DEVICE, "test", 120, 3000)
        val trJson = Json.parseObject(tr.toJson())
        check("transcript json has language+words", trJson.obj("language")?.str("code") == "hi" && trJson.int("wordCount", 0) == 5 && trJson.int("tier", 0) == 2 && trJson.double("rtf", 0.0) == 0.04)
        val back = TranscriptResult.fromJson(trJson)
        check("transcript json round trip", back.text == tr.text && back.language.language == Language.HINDI && back.words.size == 5)

        // ---- Audio
        val sine = ShortArray(16000) { (Math.sin(it * 2 * Math.PI * 440 / 16000) * 12000).toInt().toShort() }
        val pcm = PcmAudio(sine, 16000)
        val wav = Wav.decode(pcm.toWavBytes())
        check("wav encode/decode", wav.sampleRate == 16000 && wav.samples.contentEquals(sine))
        val rs = pcm.resampled(8000)
        check("resample", rs.samples.size == 8000 && rs.durationMs == 1000L)
        check("rms level", pcm.rmsDb() > -15 && pcm.rmsDb() < -8 && PcmAudio.silence(100).rmsDb() <= -100, "%.1f dBFS".format(pcm.rmsDb()))

        // ---- Segmenter (energy VAD) on a real recording if given
        if (args.isNotEmpty() && File(args[0]).exists()) {
            val audio = Wav.read(File(args[0])).resampled(16000)
            val padded = PcmAudio.concat(listOf(PcmAudio.silence(600), audio, PcmAudio.silence(1200)), 16000)
            var segments = 0; var starts = 0; var segMs = 0L
            val seg = Segmenter(EnergyVad(), 16000, 700, 15000, 250, onSpeechStart = { starts++ }, onSegment = { segments++; segMs += it.durationMs })
            val frame = 320
            var i = 0
            while (i + frame <= padded.samples.size) { seg.accept(padded.samples.copyOfRange(i, i + frame)); i += frame }
            seg.flush()
            check("segmenter cuts one utterance", segments == 1 && segMs >= audio.durationMs * 0.8, "segments=$segments starts=$starts segMs=$segMs audioMs=${audio.durationMs}")
        }

        // ---- Metrics
        check("wer/cer", Metrics.wer("नमस्ते मेरा नाम मयंक है", "नमस्ते मेरा नाम बयंक है") == 0.2 && Metrics.cer("abcd", "abce") == 0.25)

        // ---- Config + catalog
        val cfgFile = listOf(File("config/voice-config.json"), File("../config/voice-config.json")).firstOrNull { it.exists() }
        if (cfgFile != null) {
            val cfg = VoiceConfig.load(cfgFile)
            check("config loads", cfg.features.size == 2 && cfg.stt.tierPolicy == TierPolicy.DEVICE_FIRST && cfg.stt.packFor(Language.HINDI) == "dolphin-base-int8" && cfg.stt.packFor(Language.MAITHILI) == "omnilingual-300m-int8")
            check("catalog loads", cfg.packs.catalogInline.size >= 8 && cfg.packs.catalogInline.all { it.id.isNotBlank() }, "${cfg.packs.catalogInline.size} packs")
            check("catalog sha256 present for downloadable packs", cfg.packs.catalogInline.filter { it.archive?.url?.isNotBlank() == true }.all { it.archive?.sha256?.length == 64 })
        }

        // ---- bzip2 decoder on a tiny stream (hello world compressed with bzip2 -9)
        val bz = byteArrayOf(66.toByte(), 90.toByte(), 104.toByte(), 57.toByte(), 49.toByte(), 65.toByte(), 89.toByte(), 38.toByte(), 83.toByte(), 89.toByte(), 78.toByte(), 236.toByte(), 232.toByte(), 54.toByte(), 0.toByte(), 0.toByte(), 2.toByte(), 81.toByte(), 128.toByte(), 0.toByte(), 16.toByte(), 64.toByte(), 0.toByte(), 6.toByte(), 68.toByte(), 144.toByte(), 128.toByte(), 32.toByte(), 0.toByte(), 49.toByte(), 6.toByte(), 76.toByte(), 65.toByte(), 1.toByte(), 167.toByte(), 169.toByte(), 165.toByte(), 128.toByte(), 187.toByte(), 148.toByte(), 49.toByte(), 248.toByte(), 187.toByte(), 146.toByte(), 41.toByte(), 194.toByte(), 132.toByte(), 130.toByte(), 119.toByte(), 103.toByte(), 65.toByte(), 176.toByte())
        val out = try { BZip2InputStream(bz.inputStream()).readBytes().toString(Charsets.UTF_8) } catch (e: Exception) { "ERR " + e.message }
        check("bzip2 decode", out == "hello world\n", out.trim())

        println(if (failures == 0) "ALL ${samples.size + 20}+ CHECKS PASSED" else "$failures CHECK(S) FAILED")
        if (failures > 0) System.exit(1)
    }
}
