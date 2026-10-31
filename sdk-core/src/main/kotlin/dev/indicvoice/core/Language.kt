package dev.indicvoice.core

/** Writing systems the SDK can recognise in text output. */
enum class Script(val iso15924: String) {
    DEVANAGARI("Deva"), BENGALI("Beng"), GURMUKHI("Guru"), GUJARATI("Gujr"), ORIYA("Orya"),
    TAMIL("Taml"), TELUGU("Telu"), KANNADA("Knda"), MALAYALAM("Mlym"), OL_CHIKI("Olck"),
    MEETEI_MAYEK("Mtei"), ARABIC("Arab"), LATIN("Latn"), UNKNOWN("Zyyy");
}

/**
 * The 22 scheduled languages of India plus Indian English.
 *
 * `code` is the ISO 639 code used everywhere in the SDK's own API and JSON
 * (`hi`, `ta`, `mai`, ...). `bcp47` is what OS engines want (`hi-IN`).
 */
enum class Language(
    val code: String,
    val bcp47: String,
    val englishName: String,
    val nativeName: String,
    val script: Script,
) {
    ASSAMESE("as", "as-IN", "Assamese", "অসমীয়া", Script.BENGALI),
    BENGALI("bn", "bn-IN", "Bengali", "বাংলা", Script.BENGALI),
    BODO("brx", "brx-IN", "Bodo", "बड़ो", Script.DEVANAGARI),
    DOGRI("doi", "doi-IN", "Dogri", "डोगरी", Script.DEVANAGARI),
    ENGLISH("en", "en-IN", "English", "English", Script.LATIN),
    GUJARATI("gu", "gu-IN", "Gujarati", "ગુજરાતી", Script.GUJARATI),
    HINDI("hi", "hi-IN", "Hindi", "हिन्दी", Script.DEVANAGARI),
    KANNADA("kn", "kn-IN", "Kannada", "ಕನ್ನಡ", Script.KANNADA),
    KASHMIRI("ks", "ks-IN", "Kashmiri", "کٲشُر", Script.ARABIC),
    KONKANI("kok", "kok-IN", "Konkani", "कोंकणी", Script.DEVANAGARI),
    MAITHILI("mai", "mai-IN", "Maithili", "मैथिली", Script.DEVANAGARI),
    MALAYALAM("ml", "ml-IN", "Malayalam", "മലയാളം", Script.MALAYALAM),
    MANIPURI("mni", "mni-IN", "Manipuri (Meitei)", "ꯃꯤꯇꯩꯂꯣꯟ", Script.BENGALI),
    MARATHI("mr", "mr-IN", "Marathi", "मराठी", Script.DEVANAGARI),
    NEPALI("ne", "ne-IN", "Nepali", "नेपाली", Script.DEVANAGARI),
    ODIA("or", "or-IN", "Odia", "ଓଡ଼ିଆ", Script.ORIYA),
    PUNJABI("pa", "pa-IN", "Punjabi", "ਪੰਜਾਬੀ", Script.GURMUKHI),
    SANSKRIT("sa", "sa-IN", "Sanskrit", "संस्कृतम्", Script.DEVANAGARI),
    SANTALI("sat", "sat-IN", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ", Script.OL_CHIKI),
    SINDHI("sd", "sd-IN", "Sindhi", "سنڌي", Script.ARABIC),
    TAMIL("ta", "ta-IN", "Tamil", "தமிழ்", Script.TAMIL),
    TELUGU("te", "te-IN", "Telugu", "తెలుగు", Script.TELUGU),
    URDU("ur", "ur-IN", "Urdu", "اردو", Script.ARABIC);

    companion object {
        private val byCode: Map<String, Language> = buildMap {
            for (l in Language.entries) {
                put(l.code, l)
                put(l.bcp47.lowercase(), l)
                put(l.bcp47.substringBefore('-').lowercase(), l)
            }
            // Common aliases from vendors / OS engines.
            put("hin", HINDI); put("ben", BENGALI); put("tam", TAMIL); put("tel", TELUGU); put("mar", MARATHI)
            put("guj", GUJARATI); put("kan", KANNADA); put("mal", MALAYALAM); put("pan", PUNJABI); put("ori", ODIA)
            put("ory", ODIA); put("asm", ASSAMESE); put("urd", URDU); put("nep", NEPALI); put("san", SANSKRIT)
            put("snd", SINDHI); put("kas", KASHMIRI); put("eng", ENGLISH); put("od", ODIA)
            put("en-us", ENGLISH); put("en-gb", ENGLISH); put("ne-np", NEPALI); put("ur-pk", URDU)
            put("mni-mtei", MANIPURI); put("sd-deva", SINDHI); put("ks-deva", KASHMIRI)
        }

        /** Accepts `hi`, `hi-IN`, `hi_IN`, `hin`, `HINDI`, ... Returns null for unknown or "auto". */
        fun parse(raw: String?): Language? {
            if (raw.isNullOrBlank()) return null
            val norm = raw.trim().replace('_', '-').lowercase()
            if (norm == "auto" || norm == "unknown" || norm == "und") return null
            byCode[norm]?.let { return it }
            byCode[norm.substringBefore('-')]?.let { return it }
            return entries.firstOrNull { it.name.equals(norm, ignoreCase = true) }
        }

        fun fromScript(script: Script): List<Language> = entries.filter { it.script == script }
    }
}

/** Marker used in options where the caller does not know the language. */
const val LANGUAGE_AUTO = "auto"
