package dev.indicvoice.core

/**
 * Language identification for transcripts.
 *
 * Engines differ: Whisper reports a language, Dolphin/Omnilingual/IndicConformer
 * return text only, OS engines return text in the language that was requested.
 * This detector turns whatever is available into one consistent
 * [DetectedLanguage] with a `source` field so the caller knows how sure to be:
 *
 *  - `engine`     the engine itself identified the language (highest trust)
 *  - `script`     the text is in a script used by exactly one supported language
 *  - `stopwords`  shared script (Devanagari, Bengali, Arabic, Latin) resolved by function words
 *  - `hint`       shared script, no stopword evidence; fell back to the caller's language hint
 *  - `default`    nothing to go on at all
 */
object LanguageDetector {

    data class ScriptStats(val counts: Map<Script, Int>, val letters: Int) {
        val dominant: Script? get() = counts.maxByOrNull { it.value }?.key
        val purity: Double get() = if (letters == 0) 0.0 else (counts.maxOfOrNull { it.value } ?: 0).toDouble() / letters
    }

    fun scriptOf(ch: Char): Script? {
        val c = ch.code
        return when {
            c in 0x0900..0x097F || c in 0xA8E0..0xA8FF -> Script.DEVANAGARI
            c in 0x0980..0x09FF -> Script.BENGALI
            c in 0x0A00..0x0A7F -> Script.GURMUKHI
            c in 0x0A80..0x0AFF -> Script.GUJARATI
            c in 0x0B00..0x0B7F -> Script.ORIYA
            c in 0x0B80..0x0BFF -> Script.TAMIL
            c in 0x0C00..0x0C7F -> Script.TELUGU
            c in 0x0C80..0x0CFF -> Script.KANNADA
            c in 0x0D00..0x0D7F -> Script.MALAYALAM
            c in 0x1C50..0x1C7F -> Script.OL_CHIKI
            c in 0xABC0..0xABFF || c in 0xAAE0..0xAAFF -> Script.MEETEI_MAYEK
            c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF -> Script.ARABIC
            ch.isLetter() && (c < 0x0250 || c in 0x1E00..0x1EFF) -> Script.LATIN
            else -> null
        }
    }

    /**
     * Share (0..1) of letters written in scripts none of our 23 languages use — Japanese, Chinese, Korean,
     * Thai, Cyrillic, ... A multilingual model that answers in those has misheard the language.
     */
    fun foreignLetterShare(text: String): Double {
        var letters = 0; var foreign = 0; var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            if (cp > 0xFFFF || scriptOf(cp.toChar()) == null) foreign++
        }
        return if (letters == 0) 0.0 else foreign.toDouble() / letters
    }

    fun scriptStats(text: String): ScriptStats {
        val counts = HashMap<Script, Int>()
        var letters = 0
        for (ch in text) {
            val s = scriptOf(ch) ?: continue
            // Skip combining marks/digits for Latin; keep all for Indic scripts (vowel signs count).
            if (s == Script.LATIN && !ch.isLetter()) continue
            counts[s] = (counts[s] ?: 0) + 1
            letters++
        }
        return ScriptStats(counts, letters)
    }

    /**
     * @param text        transcript text
     * @param engineLang  language reported by the engine (any format), or null
     * @param hint        language the caller asked for, or null for auto
     * @param allowed     restrict guesses to this set when non-empty
     */
    fun detect(
        text: String,
        engineLang: String? = null,
        hint: Language? = null,
        allowed: Collection<Language> = emptyList(),
    ): DetectedLanguage {
        val stats = scriptStats(text)
        val script = stats.dominant ?: Script.UNKNOWN

        // 1. Engine-provided language wins, but sanity-check it against the script we see.
        Language.parse(engineLang)?.let { eng ->
            val scriptAgrees = script == Script.UNKNOWN || eng.script == script ||
                (script == Script.LATIN) // romanised output of an Indic language is still that language
            if (scriptAgrees && (allowed.isEmpty() || eng in allowed)) {
                return DetectedLanguage(eng, script, confidence = if (script == Script.LATIN && eng.script != Script.LATIN) 0.6 else 0.9, source = "engine")
            }
        }

        if (stats.letters == 0) {
            // Nothing recognised: report the hint (or Hindi) with zero confidence so callers can tell.
            val fallback = hint ?: Language.HINDI
            return DetectedLanguage(fallback, Script.UNKNOWN, 0.0, "none")
        }

        val candidates = Language.fromScript(script).filter { allowed.isEmpty() || it in allowed }
        val purity = stats.purity

        // Latin script: English, or a romanised Indian language ("Hinglish") recognised by its function words.
        if (script == Script.LATIN) {
            val scores = stopwordScores(text, Script.LATIN, listOf(Language.HINDI, Language.ENGLISH))
            val hi = scores[Language.HINDI] ?: 0
            val en = scores[Language.ENGLISH] ?: 0
            if (hi > en && (allowed.isEmpty() || Language.HINDI in allowed)) return DetectedLanguage(Language.HINDI, Script.LATIN, (0.5 + 0.1 * hi).coerceAtMost(0.9), "stopwords")
            if (hint != null && hint.script != Script.LATIN && en == 0 && (allowed.isEmpty() || hint in allowed)) return DetectedLanguage(hint, Script.LATIN, 0.5, "hint")
            return DetectedLanguage(Language.ENGLISH, Script.LATIN, if (en > 0) 0.9 else 0.6, if (en > 0) "stopwords" else "script")
        }

        // 2. Script used by exactly one language we support.
        if (candidates.size == 1) {
            return DetectedLanguage(candidates[0], script, 0.55 + 0.4 * purity, "script")
        }

        // 3. Shared script: use function-word evidence.
        if (candidates.size > 1) {
            val scores = stopwordScores(text, script, candidates)
            val best = scores.maxByOrNull { it.value }
            if (best != null && best.value > 0) {
                val total = scores.values.sum().coerceAtLeast(1)
                val share = best.value.toDouble() / total
                // Hint breaks near-ties.
                val winner = if (hint != null && hint in candidates && (scores[hint] ?: 0) >= best.value * 0.8) hint else best.key
                val conf = (0.5 + 0.45 * share * purity).coerceIn(0.3, 0.95)
                return DetectedLanguage(winner, script, conf, "stopwords")
            }
            if (hint != null && hint in candidates) return DetectedLanguage(hint, script, 0.5, "hint")
            // No evidence: choose the most widely spoken language of that script.
            val dflt = defaultForScript(script, candidates)
            return DetectedLanguage(dflt, script, 0.35, "default")
        }

        // 4. Script we know but no allowed language uses it (e.g. allowed restricted).
        val fallback = hint ?: Language.HINDI
        return DetectedLanguage(fallback, script, 0.2, if (hint != null) "hint" else "default")
    }

    private fun defaultForScript(script: Script, candidates: List<Language>): Language = when (script) {
        Script.DEVANAGARI -> candidates.firstOrNull { it == Language.HINDI } ?: candidates[0]
        Script.BENGALI -> candidates.firstOrNull { it == Language.BENGALI } ?: candidates[0]
        Script.ARABIC -> candidates.firstOrNull { it == Language.URDU } ?: candidates[0]
        Script.LATIN -> candidates.firstOrNull { it == Language.ENGLISH } ?: candidates[0]
        else -> candidates[0]
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[\\s\\p{Punct}।॥,.!?\"'()\\[\\]{}:;—–-]+")).filter { it.isNotBlank() }

    private fun stopwordScores(text: String, script: Script, candidates: List<Language>): Map<Language, Int> {
        val words = tokenize(text)
        val scores = HashMap<Language, Int>()
        for (lang in candidates) {
            val set = STOPWORDS[lang] ?: continue
            var n = 0
            for (w in words) if (w in set) n++
            // Assamese has two letters Bengali lacks; count them as strong evidence.
            if (lang == Language.ASSAMESE && script == Script.BENGALI) n += text.count { it == 'ৰ' || it == 'ৱ' } * 2
            scores[lang] = n
        }
        return scores
    }

    private val STOPWORDS: Map<Language, Set<String>> = mapOf(
        Language.HINDI to setOf("है", "हैं", "का", "की", "के", "में", "और", "नहीं", "को", "से", "पर", "यह", "वह", "हूँ", "हूं", "था", "थे", "थी", "क्या", "मैं", "हम", "आप", "तो", "भी", "ही", "कर", "किया", "लिए", "एक", "कि", "रहा", "रही", "रहे", "गया", "गई", "चाहिए", "मुझे", "तुम", "उस", "इस", "मेरे", "मेरी", "मेरा", "आपका", "आपकी", "आपके", "हमारा", "हमारे", "तुम्हारा", "कृपया", "बताइए", "बताओ", "करो", "करें", "कीजिए", "दीजिए", "अब", "कब", "कहाँ", "कहां", "कैसे", "क्यों", "कौन", "कितना", "कितने", "बहुत", "सब", "कुछ", "फिर", "जब", "तब", "अगर", "लेकिन", "या", "आज", "कल", "यहाँ", "यहां", "वहाँ", "वहां", "साथ", "बाद", "पहले", "अच्छा", "ठीक", "नया", "हो", "होगा", "होगी", "सकता", "सकती", "सकते", "करना", "करने", "देना", "लेना", "जाना", "आना", "खाता", "पैसे", "नंबर", "हुआ", "हुई", "हुए", "दो", "तीन", "चार", "पाँच", "पांच",
            // romanised (Hinglish)
            "hai", "hain", "ka", "ki", "ke", "mein", "aur", "nahi", "nahin", "kya", "main", "hum", "aap", "toh", "bhi", "mujhe", "tum", "kar", "kiya", "liye", "ek", "raha", "rahi", "chahiye", "hoon", "tha", "the", "yeh", "woh", "se", "ko", "par"),
        Language.MARATHI to setOf("आहे", "आहेत", "आणि", "नाही", "मध्ये", "च्या", "ची", "चा", "चे", "ला", "ने", "होते", "होता", "होती", "मी", "तू", "आम्ही", "तुम्ही", "कसे", "काय", "पण", "म्हणून", "झाले", "झाला", "करण्यासाठी", "आपण", "त्यांना", "त्याला", "माझा", "माझी", "माझे", "हे", "ते", "तुमचा", "तुमची", "तुमचे", "सांगा", "करा", "आता", "कधी", "कुठे", "कसा", "का", "कोण", "किती", "खूप", "सर्व", "काही", "मग", "जर", "किंवा", "उद्या", "इथे", "तिथे", "सोबत", "नंतर", "आधी", "चांगले", "नवीन", "होईल", "शकतो", "शकते", "करणे", "देणे", "घेणे", "जाणे", "येणे", "पाहिजे", "हवे", "हवा", "हवी"),
        Language.NEPALI to setOf("छ", "छन्", "हो", "होइन", "र", "मा", "ले", "लाई", "गर्न", "गरेको", "थियो", "थिए", "हुन्छ", "म", "हामी", "तपाईं", "तिमी", "पनि", "तर", "भने", "छु", "छौं", "गर्छु", "गरें", "यो", "त्यो", "उनी", "उहाँ", "मेरो", "तपाईंको", "हाम्रो", "तिम्रो", "भन्नुहोस्", "गर्नुहोस्", "अहिले", "कहिले", "कसरी", "किन", "कति", "धेरै", "सबै", "केही", "अनि", "यदि", "भोलि", "त्यहाँ", "सँग", "पछि", "अघि", "राम्रो", "नयाँ", "सक्छ", "गर्नु", "दिनु", "लिनु", "जानु", "आउनु", "चाहिन्छ", "छैन", "थिएन"),
        Language.SANSKRIT to setOf("अस्ति", "सन्ति", "च", "तु", "न", "एव", "इति", "अहम्", "त्वम्", "सः", "सा", "तत्", "एतत्", "भवति", "अपि", "यत्", "किम्", "मम", "तव", "अस्माकम्", "गच्छति", "गच्छामि", "नमः"),
        Language.MAITHILI to setOf("अछि", "छी", "छल", "छलाह", "आ", "सँ", "केर", "हम", "अहाँ", "ओ", "नहि", "मे", "लेल", "कए", "भेल", "छथि", "रहल", "हमर", "अहाँक"),
        Language.KONKANI to setOf("आसा", "आसात", "ना", "आनी", "हांव", "तूं", "तें", "कितें", "कशें", "खातीर", "म्हण", "जाल्यार", "आमी", "तुमी", "म्हजो", "म्हजी", "म्हजें", "हें", "जाता"),
        Language.DOGRI to setOf("ऐ", "दा", "दी", "दे", "गी", "कन्नै", "हा", "तुस", "असें", "नेईं", "किश", "जे", "इक", "ओह", "एह", "करदा", "करदी", "होंदा"),
        Language.BODO to setOf("नि", "खौ", "जों", "आरो", "नङा", "नों", "आं", "बे", "बै", "जाबाय", "होनो", "मोन", "थानाय", "जानाय", "बिसोर", "बिनि"),
        Language.BENGALI to setOf("এবং", "না", "আছে", "হয়", "আমি", "আপনি", "তিনি", "করা", "করে", "এই", "সেই", "কিন্তু", "তার", "আমার", "তুমি", "হবে", "ছিল", "থেকে", "জন্য", "একটি", "কি", "কে"),
        Language.ASSAMESE to setOf("আৰু", "নাই", "আছে", "হয়", "মই", "আপুনি", "তেওঁ", "কৰা", "কৰে", "কিন্তু", "তাৰ", "মোৰ", "তুমি", "হ'ব", "আছিল", "পৰা", "বাবে", "এটা", "কি", "ক"),
        Language.MANIPURI to setOf("অমসুং", "ঐ", "নঙ", "মহাক", "ঐখোয়", "নখোয়", "লৈ", "ওই", "নত্তে", "অসি", "অদু"),
        Language.URDU to setOf("ہے", "ہیں", "کا", "کی", "کے", "میں", "اور", "نہیں", "کو", "سے", "پر", "یہ", "وہ", "ہوں", "تھا", "تھے", "تھی", "کیا", "ہم", "آپ", "تو", "بھی", "ایک", "کر", "لیے", "مجھے", "چاہیے", "رہا", "رہی", "گیا"),
        Language.KASHMIRI to setOf("چھُ", "چھِ", "چھِہ", "ہٕنٛد", "تہٕ", "یِم", "یہِ", "اوس", "آسُن", "کِتھ", "کیاہ", "بہٕ", "تۄہۍ", "اسۍ", "سُ", "تِم"),
        Language.SINDHI to setOf("آهي", "آهن", "۽", "جو", "جي", "جا", "کي", "۾", "نه", "هي", "اهو", "هو", "ٿو", "ٿي", "مان", "اسان", "توهان", "ڪري", "ڪيو", "لاءِ", "هڪ"),
        Language.ENGLISH to setOf("the", "is", "are", "and", "to", "of", "in", "that", "it", "for", "on", "with", "as", "this", "was", "my", "me", "you", "your", "please", "not", "have", "has", "be", "i", "we", "they", "what", "can", "will", "do"),
    )
}

/** The language of a transcript, as the SDK reports it. */
data class DetectedLanguage(
    val language: Language,
    val script: Script,
    val confidence: Double,
    val source: String,
) : JsonSerializable {
    override fun toJsonValue(): Any? = linkedMapOf(
        "code" to language.code,
        "bcp47" to language.bcp47,
        "name" to language.englishName,
        "nativeName" to language.nativeName,
        "script" to script.iso15924,
        "confidence" to Math.round(confidence * 100) / 100.0,
        "source" to source,
    )
}
