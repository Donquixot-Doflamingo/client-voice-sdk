// IndicVoiceCore.swift
// Platform-independent part of the Indic Voice SDK, ported from sdk-core (Kotlin): languages and script/stopword
// language detection, the TranscriptResult / TtsResult / capabilities JSON shapes, WER/CER, 16-bit PCM + WAV,
// voice-config.json + catalog.json parsing, the pack manager (download, sha256, tar.bz2), the energy VAD and the
// utterance segmenter, and the tier-3 hosted STT/TTS clients.
//
// Conventions: every JSON-facing type has a `toJson()` returning `[String: Any]` whose keys match
// src/IndicVoice.types.ts exactly; JSON `null` is `NSNull()` (never a Swift nil inside `Any`), so the Expo bridge
// turns it into JavaScript `null` the same way the Android module does.

import Foundation
import CryptoKit

// MARK: - Logging

enum IVLog {
    /// 0 = debug, 1 = info, 2 = warn, 3 = error. Set from voice-config.json `logging.level`.
    static var level: Int = 1

    static func setLevel(_ name: String) {
        switch name.lowercased() {
        case "debug": level = 0
        case "info": level = 1
        case "warn", "warning": level = 2
        default: level = 3
        }
    }

    static func debug(_ msg: String) { if level <= 0 { NSLog("[IndicVoice:debug] %@", msg) } }
    static func info(_ msg: String) { if level <= 1 { NSLog("[IndicVoice] %@", msg) } }
    static func warn(_ msg: String) { if level <= 2 { NSLog("[IndicVoice:warn] %@", msg) } }
    static func error(_ msg: String) { NSLog("[IndicVoice:error] %@", msg) }
}

// MARK: - Small helpers

func nowMs() -> Int {
    return Int(Date().timeIntervalSince1970 * 1000)
}

func round2(_ x: Double) -> Double { return (x * 100).rounded() / 100 }
func round3(_ x: Double) -> Double { return (x * 1000).rounded() / 1000 }

/// JSON null for optional values placed inside `[String: Any]`.
func jsonOrNull(_ value: Any?) -> Any {
    if let v = value { return v }
    return NSNull()
}

extension Dictionary where Key == String, Value == Any {
    func obj(_ key: String) -> [String: Any]? { return self[key] as? [String: Any] }
    func list(_ key: String) -> [Any]? { return self[key] as? [Any] }
    func str(_ key: String) -> String? { return self[key] as? String }
    func bool(_ key: String, _ def: Bool) -> Bool {
        if let b = self[key] as? Bool { return b }
        if let n = self[key] as? NSNumber { return n.boolValue }
        return def
    }
    func int(_ key: String, _ def: Int) -> Int {
        if let n = self[key] as? NSNumber { return n.intValue }
        return def
    }
    func int64(_ key: String, _ def: Int64) -> Int64 {
        if let n = self[key] as? NSNumber { return n.int64Value }
        return def
    }
    func double(_ key: String, _ def: Double) -> Double {
        if let n = self[key] as? NSNumber { return n.doubleValue }
        return def
    }
    func strList(_ key: String) -> [String] {
        return (list(key) ?? []).compactMap { $0 as? String }
    }
    func objList(_ key: String) -> [[String: Any]] {
        return (list(key) ?? []).compactMap { $0 as? [String: Any] }
    }
    func strMap(_ key: String) -> [String: String] {
        var out: [String: String] = [:]
        if let m = obj(key) {
            for (k, v) in m {
                if let s = v as? String { out[k] = s }
            }
        }
        return out
    }
}

enum Json {
    static func parseObject(_ text: String) throws -> [String: Any] {
        guard let data = text.data(using: .utf8) else { throw VoiceError(.CONFIG_ERROR, "JSON is not UTF-8") }
        return try parseObject(data)
    }

    static func parseObject(_ data: Data) throws -> [String: Any] {
        let any: Any
        do {
            any = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            throw VoiceError(.CONFIG_ERROR, "Invalid JSON: \(error.localizedDescription)")
        }
        guard let obj = any as? [String: Any] else { throw VoiceError(.CONFIG_ERROR, "JSON root is not an object") }
        return obj
    }

    static func stringify(_ value: Any, pretty: Bool = false) -> String {
        guard JSONSerialization.isValidJSONObject(value) else { return "{}" }
        let opts: JSONSerialization.WritingOptions = pretty ? [.prettyPrinted] : []
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: opts) else { return "{}" }
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}

/// A boolean that several threads may set; `trySet` returns true only for the first caller.
final class AtomicFlag {
    private let lock = NSLock()
    private var value = false
    var isSet: Bool {
        lock.lock(); defer { lock.unlock() }
        return value
    }
    func trySet() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if value { return false }
        value = true
        return true
    }
}

// MARK: - Tiers, features, errors

/// Which of the three tiers produced a result.
enum Tier: Int {
    case os = 1          // the device's own speech engine (Apple Speech / AVSpeechSynthesizer)
    case onDevice = 2    // downloadable model packs running locally (sherpa-onnx)
    case hosted = 3      // the integrator's backend

    var number: Int { return rawValue }

    var label: String {
        switch self {
        case .os: return "tier1-os"
        case .onDevice: return "tier2-on-device"
        case .hosted: return "tier3-hosted"
        }
    }

    static let all: [Tier] = [.os, .onDevice, .hosted]

    static func of(_ n: Int?) -> Tier? {
        guard let n = n else { return nil }
        return Tier(rawValue: n)
    }

    /// Accepts the numbers 1/2/3 as Int, Double or NSNumber (whatever the JS bridge hands over).
    static func of(any: Any?) -> Tier? {
        if let n = any as? NSNumber { return Tier(rawValue: n.intValue) }
        return nil
    }
}

enum Feature: String {
    case stt = "STT"
    case tts = "TTS"

    static let all: [Feature] = [.stt, .tts]

    static func parse(_ raw: String) -> Feature? {
        return Feature(rawValue: raw.trimmingCharacters(in: .whitespaces).uppercased())
    }
}

enum ErrorCode: String {
    case NO_ENGINE, FEATURE_DISABLED, LANGUAGE_UNSUPPORTED, PERMISSION_DENIED, PACK_MISSING, PACK_CORRUPT
    case NETWORK, BACKEND_ERROR, TIMEOUT, ENGINE_FAILED, BAD_AUDIO, CONFIG_ERROR, CANCELLED
}

struct VoiceError: Error, CustomStringConvertible {
    let code: ErrorCode
    let message: String
    let tier: Tier?

    init(_ code: ErrorCode, _ message: String, tier: Tier? = nil) {
        self.code = code
        self.message = message
        self.tier = tier
    }

    var description: String { return "\(code.rawValue): \(message)" }

    func toJson() -> [String: Any] {
        return ["code": code.rawValue, "message": message, "tier": jsonOrNull(tier?.number)]
    }

    /// Wraps any Swift error as a VoiceError (ENGINE_FAILED unless it already is one).
    static func wrap(_ error: Error, tier: Tier? = nil) -> VoiceError {
        if let v = error as? VoiceError { return v }
        return VoiceError(.ENGINE_FAILED, String(describing: error), tier: tier)
    }
}

// MARK: - Scripts and languages

/// Writing systems the SDK can recognise in text output (ISO 15924 codes).
enum Script: String {
    case devanagari = "Deva", bengali = "Beng", gurmukhi = "Guru", gujarati = "Gujr", oriya = "Orya"
    case tamil = "Taml", telugu = "Telu", kannada = "Knda", malayalam = "Mlym", olChiki = "Olck"
    case meeteiMayek = "Mtei", arabic = "Arab", latin = "Latn", unknown = "Zyyy"

    var iso15924: String { return rawValue }

    static func parse(_ code: String?) -> Script? {
        guard let c = code else { return nil }
        return Script(rawValue: c)
    }
}

/// The 22 scheduled languages of India plus Indian English. `code` is the ISO 639 code used everywhere in the
/// SDK's own API and JSON (`hi`, `ta`, `mai`, ...); `bcp47` is what OS engines want (`hi-IN`).
struct Language: Hashable, CustomStringConvertible {
    let key: String          // enum-style name, matches the Kotlin enum (HINDI, ODIA, ...)
    let code: String
    let bcp47: String
    let englishName: String
    let nativeName: String
    let script: Script

    static func == (a: Language, b: Language) -> Bool { return a.code == b.code }
    func hash(into hasher: inout Hasher) { hasher.combine(code) }
    var description: String { return code }

    static let assamese = Language(key: "ASSAMESE", code: "as", bcp47: "as-IN", englishName: "Assamese", nativeName: "অসমীয়া", script: .bengali)
    static let bengali = Language(key: "BENGALI", code: "bn", bcp47: "bn-IN", englishName: "Bengali", nativeName: "বাংলা", script: .bengali)
    static let bodo = Language(key: "BODO", code: "brx", bcp47: "brx-IN", englishName: "Bodo", nativeName: "बड़ो", script: .devanagari)
    static let dogri = Language(key: "DOGRI", code: "doi", bcp47: "doi-IN", englishName: "Dogri", nativeName: "डोगरी", script: .devanagari)
    static let english = Language(key: "ENGLISH", code: "en", bcp47: "en-IN", englishName: "English", nativeName: "English", script: .latin)
    static let gujarati = Language(key: "GUJARATI", code: "gu", bcp47: "gu-IN", englishName: "Gujarati", nativeName: "ગુજરાતી", script: .gujarati)
    static let hindi = Language(key: "HINDI", code: "hi", bcp47: "hi-IN", englishName: "Hindi", nativeName: "हिन्दी", script: .devanagari)
    static let kannada = Language(key: "KANNADA", code: "kn", bcp47: "kn-IN", englishName: "Kannada", nativeName: "ಕನ್ನಡ", script: .kannada)
    static let kashmiri = Language(key: "KASHMIRI", code: "ks", bcp47: "ks-IN", englishName: "Kashmiri", nativeName: "کٲشُر", script: .arabic)
    static let konkani = Language(key: "KONKANI", code: "kok", bcp47: "kok-IN", englishName: "Konkani", nativeName: "कोंकणी", script: .devanagari)
    static let maithili = Language(key: "MAITHILI", code: "mai", bcp47: "mai-IN", englishName: "Maithili", nativeName: "मैथिली", script: .devanagari)
    static let malayalam = Language(key: "MALAYALAM", code: "ml", bcp47: "ml-IN", englishName: "Malayalam", nativeName: "മലയാളം", script: .malayalam)
    static let manipuri = Language(key: "MANIPURI", code: "mni", bcp47: "mni-IN", englishName: "Manipuri (Meitei)", nativeName: "ꯃꯤꯇꯩꯂꯣꯟ", script: .bengali)
    static let marathi = Language(key: "MARATHI", code: "mr", bcp47: "mr-IN", englishName: "Marathi", nativeName: "मराठी", script: .devanagari)
    static let nepali = Language(key: "NEPALI", code: "ne", bcp47: "ne-IN", englishName: "Nepali", nativeName: "नेपाली", script: .devanagari)
    static let odia = Language(key: "ODIA", code: "or", bcp47: "or-IN", englishName: "Odia", nativeName: "ଓଡ଼ିଆ", script: .oriya)
    static let punjabi = Language(key: "PUNJABI", code: "pa", bcp47: "pa-IN", englishName: "Punjabi", nativeName: "ਪੰਜਾਬੀ", script: .gurmukhi)
    static let sanskrit = Language(key: "SANSKRIT", code: "sa", bcp47: "sa-IN", englishName: "Sanskrit", nativeName: "संस्कृतम्", script: .devanagari)
    static let santali = Language(key: "SANTALI", code: "sat", bcp47: "sat-IN", englishName: "Santali", nativeName: "ᱥᱟᱱᱛᱟᱲᱤ", script: .olChiki)
    static let sindhi = Language(key: "SINDHI", code: "sd", bcp47: "sd-IN", englishName: "Sindhi", nativeName: "سنڌي", script: .arabic)
    static let tamil = Language(key: "TAMIL", code: "ta", bcp47: "ta-IN", englishName: "Tamil", nativeName: "தமிழ்", script: .tamil)
    static let telugu = Language(key: "TELUGU", code: "te", bcp47: "te-IN", englishName: "Telugu", nativeName: "తెలుగు", script: .telugu)
    static let urdu = Language(key: "URDU", code: "ur", bcp47: "ur-IN", englishName: "Urdu", nativeName: "اردو", script: .arabic)

    static let all: [Language] = [
        assamese, bengali, bodo, dogri, english, gujarati, hindi, kannada, kashmiri, konkani, maithili, malayalam,
        manipuri, marathi, nepali, odia, punjabi, sanskrit, santali, sindhi, tamil, telugu, urdu,
    ]

    private static let byCode: [String: Language] = {
        var m: [String: Language] = [:]
        for l in Language.all {
            m[l.code] = l
            m[l.bcp47.lowercased()] = l
            if let head = l.bcp47.split(separator: "-").first { m[String(head).lowercased()] = l }
        }
        // Common aliases from vendors / OS engines.
        let aliases: [String: Language] = [
            "hin": Language.hindi, "ben": Language.bengali, "tam": Language.tamil, "tel": Language.telugu, "mar": Language.marathi,
            "guj": Language.gujarati, "kan": Language.kannada, "mal": Language.malayalam, "pan": Language.punjabi, "ori": Language.odia,
            "ory": Language.odia, "asm": Language.assamese, "urd": Language.urdu, "nep": Language.nepali, "san": Language.sanskrit,
            "snd": Language.sindhi, "kas": Language.kashmiri, "eng": Language.english, "od": Language.odia,
            "en-us": Language.english, "en-gb": Language.english, "ne-np": Language.nepali, "ur-pk": Language.urdu,
            "mni-mtei": Language.manipuri, "sd-deva": Language.sindhi, "ks-deva": Language.kashmiri,
        ]
        for (k, v) in aliases { m[k] = v }
        return m
    }()

    /// Accepts `hi`, `hi-IN`, `hi_IN`, `hin`, `HINDI`, ... Returns nil for unknown, blank or "auto".
    static func parse(_ raw: String?) -> Language? {
        guard let raw = raw else { return nil }
        let norm = raw.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "_", with: "-").lowercased()
        if norm.isEmpty || norm == "auto" || norm == "unknown" || norm == "und" { return nil }
        if let l = byCode[norm] { return l }
        if let head = norm.split(separator: "-").first, let l = byCode[String(head)] { return l }
        return all.first { $0.key.lowercased() == norm }
    }

    static func fromScript(_ script: Script) -> [Language] {
        return all.filter { $0.script == script }
    }
}

let LANGUAGE_AUTO = "auto"

/// The language of a transcript, as the SDK reports it.
struct DetectedLanguage {
    let language: Language
    let script: Script
    let confidence: Double
    let source: String

    func toJson() -> [String: Any] {
        return [
            "code": language.code,
            "bcp47": language.bcp47,
            "name": language.englishName,
            "nativeName": language.nativeName,
            "script": script.iso15924,
            "confidence": round2(confidence),
            "source": source,
        ]
    }
}

// MARK: - Language detection (script + stopwords)

/// Turns whatever an engine provides (a language tag, text in some script, nothing) into one consistent
/// DetectedLanguage with a `source`: engine > script > stopwords > hint > default.
enum LanguageDetector {

    struct ScriptStats {
        let counts: [Script: Int]
        let letters: Int
        var dominant: Script? {
            var best: Script? = nil
            var bestCount = 0
            for (s, n) in counts where n > bestCount { best = s; bestCount = n }
            return best
        }
        var purity: Double {
            if letters == 0 { return 0 }
            return Double(counts.values.max() ?? 0) / Double(letters)
        }
    }

    static func isLetter(_ u: Unicode.Scalar) -> Bool {
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    static func scriptOf(_ u: Unicode.Scalar) -> Script? {
        let c = u.value
        switch c {
        case 0x0900...0x097F, 0xA8E0...0xA8FF: return .devanagari
        case 0x0980...0x09FF: return .bengali
        case 0x0A00...0x0A7F: return .gurmukhi
        case 0x0A80...0x0AFF: return .gujarati
        case 0x0B00...0x0B7F: return .oriya
        case 0x0B80...0x0BFF: return .tamil
        case 0x0C00...0x0C7F: return .telugu
        case 0x0C80...0x0CFF: return .kannada
        case 0x0D00...0x0D7F: return .malayalam
        case 0x1C50...0x1C7F: return .olChiki
        case 0xABC0...0xABFF, 0xAAE0...0xAAFF: return .meeteiMayek
        case 0x0600...0x06FF, 0x0750...0x077F, 0xFB50...0xFDFF, 0xFE70...0xFEFF: return .arabic
        default:
            if isLetter(u) && (c < 0x0250 || (c >= 0x1E00 && c <= 0x1EFF)) { return .latin }
            return nil
        }
    }

    static func scriptStats(_ text: String) -> ScriptStats {
        var counts: [Script: Int] = [:]
        var letters = 0
        for u in text.unicodeScalars {
            guard let s = scriptOf(u) else { continue }
            // Skip combining marks/digits for Latin; keep all for Indic scripts (vowel signs count).
            if s == .latin && !isLetter(u) { continue }
            counts[s, default: 0] += 1
            letters += 1
        }
        return ScriptStats(counts: counts, letters: letters)
    }

    /// - Parameters:
    ///   - text: transcript text
    ///   - engineLang: language reported by the engine (any format), or nil
    ///   - hint: language the caller asked for, or nil for auto
    ///   - allowed: restrict guesses to this set when non-empty
    static func detect(_ text: String, engineLang: String? = nil, hint: Language? = nil, allowed: [Language] = []) -> DetectedLanguage {
        let stats = scriptStats(text)
        let script = stats.dominant ?? .unknown

        // 1. Engine-provided language wins, but sanity-check it against the script we see.
        if let eng = Language.parse(engineLang) {
            let scriptAgrees = script == .unknown || eng.script == script || script == .latin // romanised Indic is still that language
            if scriptAgrees && (allowed.isEmpty || allowed.contains(eng)) {
                let conf = (script == .latin && eng.script != .latin) ? 0.6 : 0.9
                return DetectedLanguage(language: eng, script: script, confidence: conf, source: "engine")
            }
        }

        if stats.letters == 0 {
            let fallback = hint ?? Language.hindi
            return DetectedLanguage(language: fallback, script: .unknown, confidence: 0.1, source: hint != nil ? "hint" : "default")
        }

        let candidates = Language.fromScript(script).filter { allowed.isEmpty || allowed.contains($0) }
        let purity = stats.purity

        // 2. Script used by exactly one language we support.
        if candidates.count == 1 {
            return DetectedLanguage(language: candidates[0], script: script, confidence: 0.55 + 0.4 * purity, source: "script")
        }

        // 3. Shared script: use function-word evidence.
        if candidates.count > 1 {
            let scores = stopwordScores(text, script: script, candidates: candidates)
            var bestLang = candidates[0]
            var bestScore = -1
            for lang in candidates {
                let s = scores[lang] ?? 0
                if s > bestScore { bestLang = lang; bestScore = s }
            }
            if bestScore > 0 {
                let total = max(scores.values.reduce(0, +), 1)
                let share = Double(bestScore) / Double(total)
                // Hint breaks near-ties.
                var winner = bestLang
                if let h = hint, candidates.contains(h), Double(scores[h] ?? 0) >= Double(bestScore) * 0.8 { winner = h }
                let conf = min(max(0.5 + 0.45 * share * purity, 0.3), 0.95)
                return DetectedLanguage(language: winner, script: script, confidence: conf, source: "stopwords")
            }
            if let h = hint, candidates.contains(h) {
                return DetectedLanguage(language: h, script: script, confidence: 0.5, source: "hint")
            }
            // No evidence: choose the most widely spoken language of that script.
            let dflt = defaultForScript(script, candidates)
            return DetectedLanguage(language: dflt, script: script, confidence: 0.35, source: "default")
        }

        // 4. Script we know but no allowed language uses it (e.g. allowed restricted).
        if script == .latin {
            // Romanised Hindi ("Hinglish") shows up in Latin script; detect it by its function words.
            let hinglish = stopwordScores(text, script: .latin, candidates: [Language.hindi, Language.english])
            let hi = hinglish[Language.hindi] ?? 0
            let enS = hinglish[Language.english] ?? 0
            if hi > enS && (allowed.isEmpty || allowed.contains(Language.hindi)) {
                return DetectedLanguage(language: Language.hindi, script: .latin, confidence: 0.6, source: "stopwords")
            }
            return DetectedLanguage(language: Language.english, script: .latin, confidence: enS > 0 ? 0.8 : 0.5, source: enS > 0 ? "stopwords" : "default")
        }
        let fallback = hint ?? Language.hindi
        return DetectedLanguage(language: fallback, script: script, confidence: 0.2, source: hint != nil ? "hint" : "default")
    }

    private static func defaultForScript(_ script: Script, _ candidates: [Language]) -> Language {
        let preferred: Language?
        switch script {
        case .devanagari: preferred = Language.hindi
        case .bengali: preferred = Language.bengali
        case .arabic: preferred = Language.urdu
        case .latin: preferred = Language.english
        default: preferred = nil
        }
        if let p = preferred, candidates.contains(p) { return p }
        return candidates[0]
    }

    private static func isSeparator(_ u: Unicode.Scalar) -> Bool {
        if CharacterSet.whitespacesAndNewlines.contains(u) { return true }
        if u.value == 0x0964 || u.value == 0x0965 { return true } // । ॥
        if CharacterSet.punctuationCharacters.contains(u) { return true }
        if CharacterSet.symbols.contains(u) { return true }
        return false
    }

    static func tokenize(_ text: String) -> [String] {
        var out: [String] = []
        var cur = String.UnicodeScalarView()
        for u in text.lowercased().unicodeScalars {
            if isSeparator(u) {
                if !cur.isEmpty { out.append(String(cur)); cur = String.UnicodeScalarView() }
            } else {
                cur.append(u)
            }
        }
        if !cur.isEmpty { out.append(String(cur)) }
        return out
    }

    private static func stopwordScores(_ text: String, script: Script, candidates: [Language]) -> [Language: Int] {
        let words = tokenize(text)
        var scores: [Language: Int] = [:]
        for lang in candidates {
            guard let set = STOPWORDS[lang] else { continue }
            var n = 0
            for w in words where set.contains(w) { n += 1 }
            // Assamese has two letters Bengali lacks; count them as strong evidence.
            if lang == Language.assamese && script == .bengali {
                var extra = 0
                for u in text.unicodeScalars where u.value == 0x09F0 || u.value == 0x09F1 { extra += 1 } // ৰ ৱ
                n += extra * 2
            }
            scores[lang] = n
        }
        return scores
    }

    private static let STOPWORDS: [Language: Set<String>] = [
        Language.hindi: ["है", "हैं", "का", "की", "के", "में", "और", "नहीं", "को", "से", "पर", "यह", "वह", "हूँ", "हूं", "था", "थे", "थी", "क्या", "मैं", "हम", "आप", "तो", "भी", "ही", "कर", "किया", "लिए", "एक", "कि", "रहा", "रही", "रहे", "गया", "गई", "चाहिए", "मुझे", "तुम", "उस", "इस", "मेरे", "मेरी", "मेरा", "आपका", "आपकी", "आपके", "हमारा", "हमारे", "तुम्हारा", "कृपया", "बताइए", "बताओ", "करो", "करें", "कीजिए", "दीजिए", "अब", "कब", "कहाँ", "कहां", "कैसे", "क्यों", "कौन", "कितना", "कितने", "बहुत", "सब", "कुछ", "फिर", "जब", "तब", "अगर", "लेकिन", "या", "आज", "कल", "यहाँ", "यहां", "वहाँ", "वहां", "साथ", "बाद", "पहले", "अच्छा", "ठीक", "नया", "हो", "होगा", "होगी", "सकता", "सकती", "सकते", "करना", "करने", "देना", "लेना", "जाना", "आना", "खाता", "पैसे", "नंबर", "हुआ", "हुई", "हुए", "दो", "तीन", "चार", "पाँच", "पांच",
                         // romanised (Hinglish)
                         "hai", "hain", "ka", "ki", "ke", "mein", "aur", "nahi", "nahin", "kya", "main", "hum", "aap", "toh", "bhi", "mujhe", "tum", "kar", "kiya", "liye", "ek", "raha", "rahi", "chahiye", "hoon", "tha", "the", "yeh", "woh", "se", "ko", "par"],
        Language.marathi: ["आहे", "आहेत", "आणि", "नाही", "मध्ये", "च्या", "ची", "चा", "चे", "ला", "ने", "होते", "होता", "होती", "मी", "तू", "आम्ही", "तुम्ही", "कसे", "काय", "पण", "म्हणून", "झाले", "झाला", "करण्यासाठी", "आपण", "त्यांना", "त्याला", "माझा", "माझी", "माझे", "हे", "ते", "तुमचा", "तुमची", "तुमचे", "सांगा", "करा", "आता", "कधी", "कुठे", "कसा", "का", "कोण", "किती", "खूप", "सर्व", "काही", "मग", "जर", "किंवा", "उद्या", "इथे", "तिथे", "सोबत", "नंतर", "आधी", "चांगले", "नवीन", "होईल", "शकतो", "शकते", "करणे", "देणे", "घेणे", "जाणे", "येणे", "पाहिजे", "हवे", "हवा", "हवी"],
        Language.nepali: ["छ", "छन्", "हो", "होइन", "र", "मा", "ले", "लाई", "गर्न", "गरेको", "थियो", "थिए", "हुन्छ", "म", "हामी", "तपाईं", "तिमी", "पनि", "तर", "भने", "छु", "छौं", "गर्छु", "गरें", "यो", "त्यो", "उनी", "उहाँ", "मेरो", "तपाईंको", "हाम्रो", "तिम्रो", "भन्नुहोस्", "गर्नुहोस्", "अहिले", "कहिले", "कसरी", "किन", "कति", "धेरै", "सबै", "केही", "अनि", "यदि", "भोलि", "त्यहाँ", "सँग", "पछि", "अघि", "राम्रो", "नयाँ", "सक्छ", "गर्नु", "दिनु", "लिनु", "जानु", "आउनु", "चाहिन्छ", "छैन", "थिएन"],
        Language.sanskrit: ["अस्ति", "सन्ति", "च", "तु", "न", "एव", "इति", "अहम्", "त्वम्", "सः", "सा", "तत्", "एतत्", "भवति", "अपि", "यत्", "किम्", "मम", "तव", "अस्माकम्", "गच्छति", "गच्छामि", "नमः"],
        Language.maithili: ["अछि", "छी", "छल", "छलाह", "आ", "सँ", "केर", "हम", "अहाँ", "ओ", "नहि", "मे", "लेल", "कए", "भेल", "छथि", "रहल", "हमर", "अहाँक"],
        Language.konkani: ["आसा", "आसात", "ना", "आनी", "हांव", "तूं", "तें", "कितें", "कशें", "खातीर", "म्हण", "जाल्यार", "आमी", "तुमी", "म्हजो", "म्हजी", "म्हजें", "हें", "जाता"],
        Language.dogri: ["ऐ", "दा", "दी", "दे", "गी", "कन्नै", "हा", "तुस", "असें", "नेईं", "किश", "जे", "इक", "ओह", "एह", "करदा", "करदी", "होंदा"],
        Language.bodo: ["नि", "खौ", "जों", "आरो", "नङा", "नों", "आं", "बे", "बै", "जाबाय", "होनो", "मोन", "थानाय", "जानाय", "बिसोर", "बिनि"],
        Language.bengali: ["এবং", "না", "আছে", "হয়", "আমি", "আপনি", "তিনি", "করা", "করে", "এই", "সেই", "কিন্তু", "তার", "আমার", "তুমি", "হবে", "ছিল", "থেকে", "জন্য", "একটি", "কি", "কে"],
        Language.assamese: ["আৰু", "নাই", "আছে", "হয়", "মই", "আপুনি", "তেওঁ", "কৰা", "কৰে", "কিন্তু", "তাৰ", "মোৰ", "তুমি", "হ'ব", "আছিল", "পৰা", "বাবে", "এটা", "কি", "ক"],
        Language.manipuri: ["অমসুং", "ঐ", "নঙ", "মহাক", "ঐখোয়", "নখোয়", "লৈ", "ওই", "নত্তে", "অসি", "অদু"],
        Language.urdu: ["ہے", "ہیں", "کا", "کی", "کے", "میں", "اور", "نہیں", "کو", "سے", "پر", "یہ", "وہ", "ہوں", "تھا", "تھے", "تھی", "کیا", "ہم", "آپ", "تو", "بھی", "ایک", "کر", "لیے", "مجھے", "چاہیے", "رہا", "رہی", "گیا"],
        Language.kashmiri: ["چھُ", "چھِ", "چھِہ", "ہٕنٛد", "تہٕ", "یِم", "یہِ", "اوس", "آسُن", "کِتھ", "کیاہ", "بہٕ", "تۄہۍ", "اسۍ", "سُ", "تِم"],
        Language.sindhi: ["آهي", "آهن", "۽", "جو", "جي", "جا", "کي", "۾", "نه", "هي", "اهو", "هو", "ٿو", "ٿي", "مان", "اسان", "توهان", "ڪري", "ڪيو", "لاءِ", "هڪ"],
        Language.english: ["the", "is", "are", "and", "to", "of", "in", "that", "it", "for", "on", "with", "as", "this", "was", "my", "me", "you", "your", "please", "not", "have", "has", "be", "i", "we", "they", "what", "can", "will", "do"],
    ]
}

// MARK: - Results and options

/// One recognised word. Timings are optional because OS engines rarely provide them.
struct Word {
    let text: String
    var startMs: Int? = nil
    var endMs: Int? = nil
    var confidence: Double? = nil

    func toJson() -> [String: Any] {
        var m: [String: Any] = ["text": text]
        if let s = startMs { m["startMs"] = s }
        if let e = endMs { m["endMs"] = e }
        if let c = confidence { m["confidence"] = round2(c) }
        return m
    }
}

/// The STT output format: the language is always explicit, the words are listed.
struct TranscriptResult {
    let text: String
    let words: [Word]
    let language: DetectedLanguage
    let tier: Tier
    let engine: String
    let latencyMs: Int
    let audioMs: Int
    var confidence: Double? = nil
    var isFinal: Bool = true
    var raw: [String: Any] = [:]

    /// Real-time factor: processing time / audio duration (lower is better).
    var rtf: Double { return audioMs <= 0 ? 0 : Double(latencyMs) / Double(audioMs) }

    func toJson() -> [String: Any] {
        return [
            "language": language.toJson(),
            "text": text,
            "words": words.map { $0.toJson() },
            "wordCount": words.count,
            "confidence": jsonOrNull(confidence.map { round2($0) }),
            "tier": tier.number,
            "tierLabel": tier.label,
            "engine": engine,
            "latencyMs": latencyMs,
            "audioMs": audioMs,
            "rtf": round3(rtf),
            "isFinal": isFinal,
        ]
    }

    func with(language: DetectedLanguage? = nil, audioMs: Int? = nil, engine: String? = nil) -> TranscriptResult {
        return TranscriptResult(text: text, words: words, language: language ?? self.language, tier: tier, engine: engine ?? self.engine,
                                latencyMs: latencyMs, audioMs: audioMs ?? self.audioMs, confidence: confidence, isFinal: isFinal, raw: raw)
    }

    /// Builds a result from plain text: splits words, detects the language.
    static func fromText(_ text: String, tier: Tier, engine: String, latencyMs: Int, audioMs: Int, engineLang: String? = nil,
                         hint: Language? = nil, allowed: [Language] = [], words: [Word]? = nil, confidence: Double? = nil,
                         isFinal: Bool = true, raw: [String: Any] = [:]) -> TranscriptResult {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let detected = LanguageDetector.detect(cleaned, engineLang: engineLang, hint: hint, allowed: allowed)
        let ws = words ?? splitWords(cleaned)
        return TranscriptResult(text: cleaned, words: ws, language: detected, tier: tier, engine: engine, latencyMs: latencyMs,
                                audioMs: audioMs, confidence: confidence, isFinal: isFinal, raw: raw)
    }

    static let wordTrimSet = CharacterSet(charactersIn: "।॥,.!?;:\"'")

    static func splitWords(_ text: String) -> [Word] {
        return text.components(separatedBy: .whitespacesAndNewlines)
            .map { $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: wordTrimSet) }
            .filter { !$0.isEmpty }
            .map { Word(text: $0) }
    }

    /// Parses a TranscriptResult JSON object as produced by a backend (tier 3) or by this SDK.
    static func fromJson(_ m: [String: Any], tierOverride: Tier? = nil, latencyOverride: Int? = nil) -> TranscriptResult {
        let langObj = m.obj("language")
        let langCode = langObj?.str("code") ?? m.str("languageCode") ?? m.str("language_code")
        let text = m.str("text") ?? m.str("transcript") ?? ""
        let tier = tierOverride ?? Tier.of(m.int("tier", 3)) ?? .hosted
        let words: [Word] = m.objList("words").map { w in
            Word(text: w.str("text") ?? w.str("word") ?? "",
                 startMs: (w["startMs"] as? NSNumber)?.intValue,
                 endMs: (w["endMs"] as? NSNumber)?.intValue,
                 confidence: (w["confidence"] as? NSNumber)?.doubleValue)
        }.filter { !$0.text.isEmpty }
        let lang = Language.parse(langCode)
        let detected: DetectedLanguage
        if let l = lang, let lo = langObj {
            detected = DetectedLanguage(language: l, script: Script.parse(lo.str("script")) ?? l.script,
                                        confidence: lo.double("confidence", 0.8), source: lo.str("source") ?? "engine")
        } else {
            detected = LanguageDetector.detect(text, engineLang: langCode)
        }
        return TranscriptResult(
            text: text,
            words: words.isEmpty ? splitWords(text) : words,
            language: detected,
            tier: tier,
            engine: m.str("engine") ?? "backend",
            latencyMs: latencyOverride ?? m.int("latencyMs", 0),
            audioMs: m.int("audioMs", 0),
            confidence: (m["confidence"] as? NSNumber)?.doubleValue,
            isFinal: m.bool("isFinal", true),
            raw: m
        )
    }
}

/// TTS output is deliberately simple: audio in the language that was asked for.
struct TtsResult {
    let audio: PcmAudio
    let language: Language
    let voiceId: String
    let tier: Tier
    let engine: String
    let latencyMs: Int

    func toJson() -> [String: Any] {
        return [
            "language": language.code,
            "voiceId": voiceId,
            "tier": tier.number,
            "tierLabel": tier.label,
            "engine": engine,
            "latencyMs": latencyMs,
            "audioMs": audio.durationMs,
            "sampleRate": audio.sampleRate,
        ]
    }
}

struct VoiceInfo {
    let id: String
    let language: Language
    let tier: Tier
    let name: String
    var gender: String? = nil
    var offline: Bool = true
    var quality: String? = nil
    var installed: Bool = true

    func toJson() -> [String: Any] {
        return [
            "id": id, "language": language.code, "tier": tier.number, "name": name,
            "gender": jsonOrNull(gender), "offline": offline, "quality": jsonOrNull(quality), "installed": installed,
        ]
    }
}

/// Why an engine is (not) usable right now.
struct Availability {
    let available: Bool
    var reason: String? = nil
    var details: [String: Any] = [:]

    static let yes = Availability(available: true)
    static func no(_ reason: String, _ details: [String: Any] = [:]) -> Availability {
        return Availability(available: false, reason: reason, details: details)
    }

    func toJson() -> [String: Any] {
        return ["available": available, "reason": jsonOrNull(reason), "details": details]
    }
}

/// Options for one transcription call.
struct SttOptions {
    var language: Language? = nil      // nil = auto-detect
    var tier: Tier? = nil              // nil = follow policy
    var keyterms: [String] = []
    var allowedLanguages: [Language] = []
    var packId: String? = nil
}

struct TtsOptions {
    var language: Language
    var text: String
    var tier: Tier? = nil
    var voiceId: String? = nil
    var speed: Double = 1.0
    var sampleRate: Int? = nil
    var packId: String? = nil
}

// MARK: - Audio

/// 16-bit mono PCM audio. All engines in the SDK speak this type.
struct PcmAudio {
    let samples: [Int16]
    let sampleRate: Int

    var durationMs: Int { return sampleRate <= 0 ? 0 : samples.count * 1000 / sampleRate }

    func toFloat() -> [Float] {
        var out = [Float](repeating: 0, count: samples.count)
        for i in 0..<samples.count { out[i] = Float(samples[i]) / 32768 }
        return out
    }

    /// Root-mean-square level in dBFS.
    func rmsDb() -> Double {
        if samples.isEmpty { return -120 }
        var acc = 0.0
        for s in samples {
            let f = Double(s) / 32768
            acc += f * f
        }
        let rms = (acc / Double(samples.count)).squareRoot()
        return rms <= 1e-9 ? -120 : 20 * log10(rms)
    }

    /// Linear-interpolation resampler (good enough for speech; engines are tolerant).
    func resampled(_ targetRate: Int) -> PcmAudio {
        if targetRate == sampleRate { return self }
        if samples.isEmpty || sampleRate <= 0 || targetRate <= 0 { return PcmAudio(samples: [], sampleRate: targetRate) }
        let ratio = Double(sampleRate) / Double(targetRate)
        let n = Int(Double(samples.count) / ratio)
        var out = [Int16](repeating: 0, count: n)
        for i in 0..<n {
            let pos = Double(i) * ratio
            let i0 = Int(pos)
            let i1 = min(i0 + 1, samples.count - 1)
            let frac = pos - Double(i0)
            let v = Double(samples[i0]) * (1 - frac) + Double(samples[i1]) * frac
            out[i] = Int16(max(-32768, min(32767, v)))
        }
        return PcmAudio(samples: out, sampleRate: targetRate)
    }

    func toBytesLE() -> Data {
        var data = Data(capacity: samples.count * 2)
        for s in samples {
            let u = UInt16(bitPattern: s)
            data.append(UInt8(u & 0xFF))
            data.append(UInt8(u >> 8))
        }
        return data
    }

    func toWavData() -> Data { return Wav.encode(self) }

    func writeWav(to url: URL) throws {
        let dir = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        try toWavData().write(to: url, options: [.atomic])
    }

    static func fromFloat(_ f: [Float], sampleRate: Int) -> PcmAudio {
        var out = [Int16](repeating: 0, count: f.count)
        for i in 0..<f.count {
            let v = f[i] * 32767
            out[i] = v.isFinite ? Int16(max(-32768, min(32767, v))) : 0
        }
        return PcmAudio(samples: out, sampleRate: sampleRate)
    }

    static func fromBytesLE(_ bytes: Data, sampleRate: Int) -> PcmAudio {
        let n = bytes.count / 2
        var out = [Int16](repeating: 0, count: n)
        var i = bytes.startIndex
        for k in 0..<n {
            let lo = UInt16(bytes[i])
            let hi = UInt16(bytes[i + 1])
            out[k] = Int16(bitPattern: lo | (hi << 8))
            i += 2
        }
        return PcmAudio(samples: out, sampleRate: sampleRate)
    }

    static func silence(ms: Int, sampleRate: Int = 16000) -> PcmAudio {
        return PcmAudio(samples: [Int16](repeating: 0, count: sampleRate * ms / 1000), sampleRate: sampleRate)
    }
}

/// Minimal RIFF/WAVE reader and writer (PCM 8/16/24/32-bit int or 32-bit float, any channel count -> mono).
enum Wav {
    static func encode(_ audio: PcmAudio) -> Data {
        let dataLen = audio.samples.count * 2
        var d = Data(capacity: 44 + dataLen)
        func u32(_ v: UInt32) { d.append(UInt8(v & 0xFF)); d.append(UInt8((v >> 8) & 0xFF)); d.append(UInt8((v >> 16) & 0xFF)); d.append(UInt8((v >> 24) & 0xFF)) }
        func u16(_ v: UInt16) { d.append(UInt8(v & 0xFF)); d.append(UInt8(v >> 8)) }
        d.append(contentsOf: Array("RIFF".utf8)); u32(UInt32(36 + dataLen)); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1)
        u32(UInt32(audio.sampleRate)); u32(UInt32(audio.sampleRate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(UInt32(dataLen))
        d.append(audio.toBytesLE())
        return d
    }

    static func decode(_ bytes: Data) throws -> PcmAudio {
        let b = [UInt8](bytes)
        func tag(_ at: Int) -> String { return at + 4 <= b.count ? String(decoding: b[at..<(at + 4)], as: UTF8.self) : "" }
        func u32(_ at: Int) -> Int { return at + 4 <= b.count ? Int(b[at]) | Int(b[at + 1]) << 8 | Int(b[at + 2]) << 16 | Int(b[at + 3]) << 24 : 0 }
        func u16(_ at: Int) -> Int { return at + 2 <= b.count ? Int(b[at]) | Int(b[at + 1]) << 8 : 0 }
        if b.count < 12 || tag(0) != "RIFF" { throw VoiceError(.BAD_AUDIO, "Not a RIFF file") }
        if tag(8) != "WAVE" { throw VoiceError(.BAD_AUDIO, "Not a WAVE file") }
        var channels = 1, rate = 16000, bits = 16, format = 1
        var dataStart = -1, dataLen = 0
        var pos = 12
        while pos + 8 <= b.count {
            let id = tag(pos)
            let len = u32(pos + 4)
            pos += 8
            let chunkLen = (len < 0 || len > b.count - pos) ? b.count - pos : len
            if id == "fmt " {
                format = u16(pos); channels = u16(pos + 2); rate = u32(pos + 4); bits = u16(pos + 14)
            } else if id == "data" {
                dataStart = pos; dataLen = chunkLen
            }
            pos += chunkLen
            if chunkLen % 2 == 1 { pos += 1 }
            if dataStart >= 0 { break }
        }
        if dataStart < 0 { throw VoiceError(.BAD_AUDIO, "WAV has no data chunk") }
        if format != 1 && format != 3 && format != 0xFFFE { throw VoiceError(.BAD_AUDIO, "Only PCM WAV is supported (format=\(format))") }
        if channels <= 0 { throw VoiceError(.BAD_AUDIO, "WAV has no channels") }
        let bytesPerSample = bits / 8
        if bytesPerSample <= 0 { throw VoiceError(.BAD_AUDIO, "Unsupported bit depth \(bits)") }
        let frames = dataLen / (channels * bytesPerSample)
        var out = [Int16](repeating: 0, count: frames)
        var p = dataStart
        for f in 0..<frames {
            var acc = 0
            for _ in 0..<channels {
                var v = 0
                switch bits {
                case 16:
                    v = Int(Int16(bitPattern: UInt16(u16(p))))
                case 8:
                    v = (Int(b[p]) - 128) << 8
                case 24:
                    let raw = Int(b[p]) | Int(b[p + 1]) << 8 | Int(b[p + 2]) << 16
                    v = (raw >= 0x800000 ? raw - 0x1000000 : raw) >> 8
                case 32:
                    if format == 3 {
                        let bitsU = UInt32(u32(p))
                        let scaled = Float(bitPattern: bitsU) * 32767
                        v = scaled.isFinite ? Int(max(-32768, min(32767, scaled))) : 0
                    } else {
                        v = Int(Int32(bitPattern: UInt32(u32(p)))) >> 16
                    }
                default:
                    throw VoiceError(.BAD_AUDIO, "Unsupported bit depth \(bits)")
                }
                acc += v
                p += bytesPerSample
            }
            out[f] = Int16(max(-32768, min(32767, acc / channels)))
        }
        return PcmAudio(samples: out, sampleRate: rate)
    }

    static func read(_ url: URL) throws -> PcmAudio {
        let data: Data
        do { data = try Data(contentsOf: url) } catch { throw VoiceError(.BAD_AUDIO, "Cannot read \(url.path): \(error.localizedDescription)") }
        return try decode(data)
    }

    /// Accepts plain paths and file:// URLs (as JavaScript may pass either).
    static func read(path: String) throws -> PcmAudio {
        if path.hasPrefix("file://"), let u = URL(string: path) { return try read(u) }
        return try read(URL(fileURLWithPath: path))
    }
}

// MARK: - Metrics (WER / CER)

enum Metrics {
    private static let punct: CharacterSet = CharacterSet.punctuationCharacters.union(CharacterSet(charactersIn: "।॥\"“”‘’"))

    static func normalize(_ s: String) -> String {
        var scalars = String.UnicodeScalarView()
        for u in s.lowercased().unicodeScalars {
            if punct.contains(u) { scalars.append(" ") } else { scalars.append(u) }
        }
        return String(scalars).components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
    }

    static func wer(_ reference: String, _ hypothesis: String) -> Double {
        let r = normalize(reference).split(separator: " ").map { String($0) }
        let h = normalize(hypothesis).split(separator: " ").map { String($0) }
        if r.isEmpty { return h.isEmpty ? 0 : 1 }
        return Double(levenshtein(r, h)) / Double(r.count)
    }

    static func cer(_ reference: String, _ hypothesis: String) -> Double {
        let r = normalize(reference).replacingOccurrences(of: " ", with: "").map { String($0) }
        let h = normalize(hypothesis).replacingOccurrences(of: " ", with: "").map { String($0) }
        if r.isEmpty { return h.isEmpty ? 0 : 1 }
        return Double(levenshtein(r, h)) / Double(r.count)
    }

    private static func levenshtein(_ a: [String], _ b: [String]) -> Int {
        var prev = Array(0...b.count)
        var cur = [Int](repeating: 0, count: b.count + 1)
        if a.isEmpty { return b.count }
        for i in 1...a.count {
            cur[0] = i
            if b.count > 0 {
                for j in 1...b.count {
                    let cost = a[i - 1] == b[j - 1] ? 0 : 1
                    cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
            }
            prev = cur
        }
        return prev[b.count]
    }
}

// MARK: - Configuration (voice-config.json)

enum TierPolicy: String {
    case DEVICE_FIRST, DEVICE_ONLY, HOSTED_FIRST, HOSTED_ONLY, ORDER
    static func parse(_ s: String?) -> TierPolicy { return TierPolicy(rawValue: (s ?? "").uppercased()) ?? .DEVICE_FIRST }
}

enum VoicePolicy: String {
    case OS_FIRST, CONSISTENT, DEVICE_ONLY, HOSTED_FIRST, HOSTED_ONLY, ORDER
    static func parse(_ s: String?) -> VoicePolicy { return VoicePolicy(rawValue: (s ?? "").uppercased()) ?? .OS_FIRST }
}

struct HostedConfig {
    var enabled = true
    var backendUrl = ""
    var path = "/v1/stt"
    var timeoutMs = 20000
    var authHeader = "Authorization"
    var authValuePrefix = "Bearer "
    var sendFormat = "wav"

    static func fromMap(_ m: [String: Any], pathKey: String, defaultPath: String) -> HostedConfig {
        var c = HostedConfig()
        c.enabled = m.bool("enabled", true)
        c.backendUrl = m.str("backendUrl") ?? ""
        c.path = m.str(pathKey) ?? defaultPath
        c.timeoutMs = m.int("timeoutMs", 20000)
        c.authHeader = m.str("authHeader") ?? "Authorization"
        c.authValuePrefix = m.str("authValuePrefix") ?? "Bearer "
        c.sendFormat = m.str("sendFormat") ?? "wav"
        return c
    }
}

struct LiveConfig {
    var endpointSilenceMs = 700
    var maxUtteranceMs = 15000
    var vadThreshold = 0.5
    var minSpeechMs = 250
}

struct SttConfig {
    var tierPolicy: TierPolicy = .DEVICE_FIRST
    var tierOrder: [Tier] = [.os, .onDevice, .hosted]
    var fallbackOnError = true
    var fallbackOnLowConfidence = 0.0
    var languageDetection = true
    var live = LiveConfig()
    var tier1Enabled = true
    var tier1PreferOnDevice = true
    var tier1AllowOnline = true
    var tier2Enabled = true
    var tier2NumThreads = 2
    var tier2VadPack = "silero-vad"
    var tier2DefaultPack = ""
    var tier2PackByLanguage: [String: String] = [:]
    var tier3 = HostedConfig()

    func packFor(_ language: Language?) -> String? {
        let key = language?.code ?? "auto"
        if let p = tier2PackByLanguage[key] { return p }
        if let p = tier2PackByLanguage["*"] { return p }
        return tier2DefaultPack.isEmpty ? nil : tier2DefaultPack
    }

    static func parseTiers(_ list: [Any]?) -> [Tier]? {
        guard let l = list else { return nil }
        let tiers = l.compactMap { Tier.of(any: $0) }
        return tiers.isEmpty ? nil : tiers
    }

    static func fromMap(_ m: [String: Any]) -> SttConfig {
        var c = SttConfig()
        let t1 = m.obj("tier1") ?? [:]
        let t2 = m.obj("tier2") ?? [:]
        let live = m.obj("live") ?? [:]
        c.tierPolicy = TierPolicy.parse(m.str("tierPolicy"))
        c.tierOrder = parseTiers(m.list("tierOrder")) ?? [.os, .onDevice, .hosted]
        c.fallbackOnError = m.bool("fallbackOnError", true)
        c.fallbackOnLowConfidence = m.double("fallbackOnLowConfidence", 0)
        c.languageDetection = m.obj("languageDetection")?.bool("enabled", true) ?? true
        c.live = LiveConfig(endpointSilenceMs: live.int("endpointSilenceMs", 700), maxUtteranceMs: live.int("maxUtteranceMs", 15000),
                            vadThreshold: live.double("vadThreshold", 0.5), minSpeechMs: live.int("minSpeechMs", 250))
        c.tier1Enabled = t1.bool("enabled", true)
        c.tier1PreferOnDevice = t1.bool("preferOnDevice", true)
        c.tier1AllowOnline = t1.bool("allowOnlineOsEngine", true)
        c.tier2Enabled = t2.bool("enabled", true)
        c.tier2NumThreads = t2.int("numThreads", 2)
        c.tier2VadPack = t2.str("vadPack") ?? "silero-vad"
        c.tier2DefaultPack = t2.str("defaultPack") ?? ""
        c.tier2PackByLanguage = t2.strMap("packByLanguage")
        c.tier3 = HostedConfig.fromMap(m.obj("tier3") ?? [:], pathKey: "sttPath", defaultPath: "/v1/stt")
        return c
    }
}

struct TtsConfig {
    var voicePolicy: VoicePolicy = .OS_FIRST
    var tierOrder: [Tier] = [.os, .onDevice, .hosted]
    var fallbackOnError = true
    var sampleRate = 22050
    var tier1Enabled = true
    var tier1PreferOffline = true
    var tier2Enabled = true
    var tier2NumThreads = 2
    var tier2PackByLanguage: [String: String] = [:]
    var tier3 = HostedConfig(path: "/v1/tts")
    var preferredVoices: [String: String] = [:]

    func packFor(_ language: Language) -> String? {
        return tier2PackByLanguage[language.code] ?? tier2PackByLanguage["*"]
    }

    static func fromMap(_ m: [String: Any]) -> TtsConfig {
        var c = TtsConfig()
        let t1 = m.obj("tier1") ?? [:]
        let t2 = m.obj("tier2") ?? [:]
        c.voicePolicy = VoicePolicy.parse(m.str("voicePolicy"))
        c.tierOrder = SttConfig.parseTiers(m.list("tierOrder")) ?? [.os, .onDevice, .hosted]
        c.fallbackOnError = m.bool("fallbackOnError", true)
        c.sampleRate = m.int("sampleRate", 22050)
        c.tier1Enabled = t1.bool("enabled", true)
        c.tier1PreferOffline = t1.bool("preferOffline", true)
        c.tier2Enabled = t2.bool("enabled", true)
        c.tier2NumThreads = t2.int("numThreads", 2)
        c.tier2PackByLanguage = t2.strMap("packByLanguage")
        c.tier3 = HostedConfig.fromMap(m.obj("tier3") ?? [:], pathKey: "ttsPath", defaultPath: "/v1/tts")
        c.preferredVoices = m.strMap("preferredVoices").filter { !$0.value.isEmpty }
        return c
    }
}

struct PacksConfig {
    var catalogUrl = ""
    var catalogRefreshHours = 24
    var storageDir = ""
    var autoDownloadOnWifi = false
    var catalogInline: [PackSpec] = []
}

/// Typed view of voice-config.json. Secrets never live here; the backend URL/token are injected at runtime.
struct VoiceConfig {
    var version = 1
    var features: Set<Feature> = [.stt, .tts]
    var defaultLanguage: Language = .hindi
    var allowedLanguages: [Language] = Language.all
    var stt = SttConfig()
    var tts = TtsConfig()
    var packs = PacksConfig()
    var logLevel = "info"
    var redactTranscripts = true
    var raw: [String: Any] = [:]

    /// `inlineResolver` loads a catalog file referenced by name from `packs.catalogInline` (e.g. "./catalog.json").
    static func fromJson(_ text: String, inlineResolver: (String) -> Data?) throws -> VoiceConfig {
        return try fromMap(try Json.parseObject(text), inlineResolver: inlineResolver)
    }

    static func fromMap(_ m: [String: Any], inlineResolver: (String) -> Data?) throws -> VoiceConfig {
        var c = VoiceConfig()
        if let features = m.obj("features") {
            var set = Set<Feature>()
            if features.bool("stt", true) { set.insert(.stt) }
            if features.bool("tts", true) { set.insert(.tts) }
            c.features = set
        }
        let allowed = m.strList("allowedLanguages").compactMap { Language.parse($0) }
        c.allowedLanguages = allowed.isEmpty ? Language.all : allowed
        c.version = m.int("version", 1)
        c.defaultLanguage = Language.parse(m.str("defaultLanguage")) ?? .hindi
        c.stt = SttConfig.fromMap(m.obj("stt") ?? [:])
        c.tts = TtsConfig.fromMap(m.obj("tts") ?? [:])
        let packsObj = m.obj("packs") ?? [:]
        var inline: [PackSpec] = []
        if let s = packsObj["catalogInline"] as? String {
            if let data = inlineResolver(s) {
                inline = try PackCatalog.parse(data)
            } else {
                IVLog.warn("catalogInline '\(s)' not found in the app bundle")
            }
        } else if let obj = packsObj["catalogInline"] as? [String: Any] {
            inline = try PackCatalog.parseMap(obj)
        } else if let arr = packsObj["catalogInline"] as? [Any] {
            inline = try PackCatalog.parseMap(["packs": arr])
        }
        c.packs = PacksConfig(catalogUrl: packsObj.str("catalogUrl") ?? "", catalogRefreshHours: packsObj.int("catalogRefreshHours", 24),
                              storageDir: packsObj.str("storageDir") ?? "", autoDownloadOnWifi: packsObj.bool("autoDownloadOnWifi", false),
                              catalogInline: inline)
        c.logLevel = m.obj("logging")?.str("level") ?? "info"
        c.redactTranscripts = m.obj("logging")?.bool("redactTranscripts", true) ?? true
        c.raw = m
        return c
    }
}

// MARK: - Pack catalog

struct ArchiveSpec {
    let url: String
    let format: String
    let sha256: String?
    let stripTopDir: Bool
}

struct FileSpec {
    let path: String
    let url: String
    let sha256: String?
    let sizeBytes: Int64
}

/// One entry of catalog.json.
struct PackSpec {
    let id: String
    let kind: String          // stt | tts | vad
    let engine: String        // dolphin-ctc | omnilingual-ctc | whisper | nemo-ctc | vits | kokoro | silero-vad | ...
    let name: String
    let languages: [Language]
    let sizeBytes: Int64
    let license: String
    let commercialUse: Bool
    let archive: ArchiveSpec?
    let files: [FileSpec]
    let model: [String: Any]  // engine-specific file names relative to the pack dir
    let notes: String
    let version: String
    let minRamMb: Int
    let raw: [String: Any]

    var languageCodes: [String] { return languages.map { $0.code } }
    var sizeMb: Int { return Int((Double(sizeBytes) / 1048576.0).rounded()) }

    func supports(_ language: Language?) -> Bool {
        guard let l = language else { return true }
        return languages.isEmpty || languages.contains(l)
    }

    func toJson() -> [String: Any] {
        return [
            "id": id, "kind": kind, "engine": engine, "name": name, "languages": languageCodes,
            "sizeBytes": sizeBytes, "sizeMb": sizeMb, "license": license, "commercialUse": commercialUse,
            "version": version, "notes": notes,
        ]
    }
}

enum PackCatalog {
    static func parse(_ data: Data) throws -> [PackSpec] { return try parseMap(try Json.parseObject(data)) }

    static func parseMap(_ m: [String: Any]) throws -> [PackSpec] {
        var out: [PackSpec] = []
        for p in m.objList("packs") {
            guard let id = p.str("id") else { throw VoiceError(.CONFIG_ERROR, "pack without id") }
            var arch: ArchiveSpec? = nil
            if let a = p.obj("archive") {
                let url = a.str("url") ?? ""
                arch = ArchiveSpec(url: url, format: a.str("format") ?? guessFormat(url), sha256: a.str("sha256"), stripTopDir: a.bool("stripTopDir", true))
            }
            let files = p.objList("files").map { f in FileSpec(path: f.str("path") ?? "", url: f.str("url") ?? "", sha256: f.str("sha256"), sizeBytes: f.int64("sizeBytes", 0)) }
            out.append(PackSpec(
                id: id,
                kind: p.str("kind") ?? "stt",
                engine: p.str("engine") ?? "",
                name: p.str("name") ?? id,
                languages: p.strList("languages").compactMap { Language.parse($0) },
                sizeBytes: p.int64("sizeBytes", 0),
                license: p.str("license") ?? "",
                commercialUse: p.bool("commercialUse", true),
                archive: arch,
                files: files,
                model: p.obj("model") ?? [:],
                notes: p.str("notes") ?? "",
                version: p.str("version") ?? "1",
                minRamMb: p.int("minRamMb", 0),
                raw: p
            ))
        }
        return out
    }

    static func guessFormat(_ url: String) -> String {
        if url.hasSuffix(".tar.bz2") || url.hasSuffix(".tbz2") { return "tar.bz2" }
        if url.hasSuffix(".tar.gz") || url.hasSuffix(".tgz") { return "tar.gz" }
        if url.hasSuffix(".zip") { return "zip" }
        if url.hasSuffix(".tar") { return "tar" }
        return "file"
    }
}

// MARK: - HTTP

enum Http {
    static let userAgent = "indicvoice-ios/0.1"

    struct Response {
        let status: Int
        let headers: [String: String]
        let body: Data
        var text: String { return String(data: body, encoding: .utf8) ?? "" }
        var ok: Bool { return (200...299).contains(status) }
        func header(_ name: String) -> String? {
            for (k, v) in headers where k.lowercased() == name.lowercased() { return v }
            return nil
        }
    }

    static func request(_ method: String, _ url: String, body: Data? = nil, contentType: String? = nil,
                        headers: [String: String] = [:], timeoutMs: Int = 20000) throws -> Response {
        guard let u = URL(string: url) else { throw VoiceError(.NETWORK, "Bad URL: \(url)") }
        var req = URLRequest(url: u)
        req.httpMethod = method
        req.timeoutInterval = TimeInterval(max(timeoutMs, 1000)) / 1000
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json, audio/wav, */*", forHTTPHeaderField: "Accept")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let b = body {
            req.httpBody = b
            if let ct = contentType { req.setValue(ct, forHTTPHeaderField: "Content-Type") }
        }
        let sem = DispatchSemaphore(value: 0)
        let box = ResponseBox()
        let task = URLSession.shared.dataTask(with: req) { data, response, error in
            box.data = data
            box.response = response
            box.error = error
            sem.signal()
        }
        task.resume()
        if sem.wait(timeout: .now() + .milliseconds(timeoutMs + 5000)) == .timedOut {
            task.cancel()
            throw VoiceError(.TIMEOUT, "\(method) \(url) timed out after \(timeoutMs) ms")
        }
        if let e = box.error {
            if let ue = e as? URLError, ue.code == .timedOut { throw VoiceError(.TIMEOUT, "\(method) \(url) timed out after \(timeoutMs) ms") }
            throw VoiceError(.NETWORK, "\(method) \(url) failed: \(e.localizedDescription)")
        }
        guard let http = box.response as? HTTPURLResponse else { throw VoiceError(.NETWORK, "\(method) \(url): no HTTP response") }
        var hdrs: [String: String] = [:]
        for (k, v) in http.allHeaderFields {
            if let ks = k as? String, let vs = v as? String { hdrs[ks] = vs }
        }
        return Response(status: http.statusCode, headers: hdrs, body: box.data ?? Data())
    }

    static func getText(_ url: String, headers: [String: String] = [:], timeoutMs: Int = 15000) throws -> String {
        let r = try request("GET", url, headers: headers, timeoutMs: timeoutMs)
        if !r.ok { throw VoiceError(.NETWORK, "GET \(url) -> HTTP \(r.status)") }
        return r.text
    }

    /// Downloads to `dest` (replacing it), verifying the SHA-256 when given. Retries transient network errors.
    static func download(_ url: String, to dest: URL, expectedSha256: String?, progress: ((Int64, Int64) -> Void)?) throws {
        guard let u = URL(string: url) else { throw VoiceError(.NETWORK, "Bad URL: \(url)") }
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: nil)
        var attempt = 0
        while true {
            attempt += 1
            do {
                try Downloader(dest: dest, progress: progress).run(u)
                break
            } catch let e as VoiceError {
                if e.code != .NETWORK || attempt >= 3 { throw e }
                IVLog.warn("download attempt \(attempt) failed: \(e.message); retrying")
            }
        }
        if let expected = expectedSha256, !expected.isEmpty {
            let actual = try sha256(of: dest)
            if actual.lowercased() != expected.lowercased() {
                try? FileManager.default.removeItem(at: dest)
                throw VoiceError(.PACK_CORRUPT, "SHA-256 mismatch for \(url): expected \(expected), got \(actual)")
            }
        }
    }

    static func sha256(of url: URL) throws -> String {
        guard let fh = FileHandle(forReadingAtPath: url.path) else { throw VoiceError(.PACK_CORRUPT, "cannot open \(url.lastPathComponent)") }
        defer { fh.closeFile() }
        var hasher = SHA256()
        while true {
            let chunk = fh.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

final class ResponseBox {
    var data: Data?
    var response: URLResponse?
    var error: Error?
}

/// One blocking download with progress, on top of a delegate-based URLSession.
final class Downloader: NSObject, URLSessionDownloadDelegate {
    private let dest: URL
    private let progress: ((Int64, Int64) -> Void)?
    private let sem = DispatchSemaphore(value: 0)
    private var failure: Error?
    private var moved = false
    private var httpStatus = 0

    init(dest: URL, progress: ((Int64, Int64) -> Void)?) {
        self.dest = dest
        self.progress = progress
    }

    func run(_ url: URL) throws {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 6 * 3600
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        var req = URLRequest(url: url)
        req.setValue(Http.userAgent, forHTTPHeaderField: "User-Agent")
        let task = session.downloadTask(with: req)
        task.resume()
        sem.wait()
        session.finishTasksAndInvalidate()
        if let e = failure {
            if let v = e as? VoiceError { throw v }
            if let ue = e as? URLError, ue.code == .timedOut { throw VoiceError(.NETWORK, "Download timed out: \(url)") }
            throw VoiceError(.NETWORK, "Download failed: \(e.localizedDescription)")
        }
        if !(200...299).contains(httpStatus) { throw VoiceError(.NETWORK, "GET \(url) -> HTTP \(httpStatus)") }
        if !moved { throw VoiceError(.NETWORK, "Download produced no file: \(url)") }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        progress?(totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        httpStatus = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(httpStatus) else { return }
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: location, to: dest)
            moved = true
        } catch {
            failure = VoiceError(.NETWORK, "Cannot store download: \(error.localizedDescription)")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let e = error, failure == nil { failure = e }
        if httpStatus == 0 { httpStatus = (task.response as? HTTPURLResponse)?.statusCode ?? 0 }
        sem.signal()
    }
}

// MARK: - Archives (tar, tar.bz2)

enum Archives {
    /// Extracts `archive` into `dest`. Supported: tar.bz2 (libbz2 via IVBZip2), tar. (zip / tar.gz are not needed by
    /// the catalog and are not implemented on iOS.)
    static func extract(archive: URL, format: String, dest: URL, stripTopDir: Bool, progress: ((String, Int64) -> Void)?) throws {
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true, attributes: nil)
        switch format {
        case "tar.bz2":
            let tar = archive.deletingPathExtension().appendingPathExtension("tar")
            try? FileManager.default.removeItem(at: tar)
            do {
                try IVBZip2.decompressFile(at: archive.path, to: tar.path)
            } catch {
                throw VoiceError(.PACK_CORRUPT, "bzip2 decompression failed: \(error.localizedDescription)")
            }
            defer { try? FileManager.default.removeItem(at: tar) }
            try TarExtractor.extract(tarPath: tar, dest: dest, stripTopDir: stripTopDir, progress: progress)
        case "tar":
            try TarExtractor.extract(tarPath: archive, dest: dest, stripTopDir: stripTopDir, progress: progress)
        default:
            throw VoiceError(.PACK_CORRUPT, "Archive format '\(format)' is not supported on iOS (use tar.bz2)")
        }
    }
}

/// Streaming ustar/GNU/pax tar reader: regular files only ('L' long names and pax `path` honoured), everything
/// else skipped. Memory use is bounded by the 1 MB copy buffer regardless of the archive size.
enum TarExtractor {
    static func extract(tarPath: URL, dest: URL, stripTopDir: Bool, progress: ((String, Int64) -> Void)?) throws {
        guard let fh = FileHandle(forReadingAtPath: tarPath.path) else { throw VoiceError(.PACK_CORRUPT, "cannot open \(tarPath.lastPathComponent)") }
        defer { fh.closeFile() }
        var total: Int64 = 0
        var longName: String? = nil
        var paxPath: String? = nil
        while true {
            let header = [UInt8](fh.readData(ofLength: 512))
            if header.count < 512 { break }
            if header.allSatisfy({ $0 == 0 }) { break }
            var name = cstr(header, 0, 100)
            let size = parseOctal(header, 124, 12)
            let type = header[156]
            let prefix = cstr(header, 345, 155)
            if !prefix.isEmpty { name = prefix + "/" + name }
            if let ln = longName { name = ln; longName = nil }
            if let pp = paxPath { name = pp; paxPath = nil }
            let padded = (size + 511) / 512 * 512
            switch type {
            case UInt8(ascii: "L"):
                longName = readString(fh, size)
                skip(fh, padded - size)
            case UInt8(ascii: "x"), UInt8(ascii: "g"):
                let pax = readString(fh, size)
                skip(fh, padded - size)
                if type == UInt8(ascii: "x") { paxPath = parsePax(pax)["path"] }
            case UInt8(ascii: "0"), 0, UInt8(ascii: "7"):
                if let target = try safeTarget(dest, name, stripTopDir) {
                    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: nil)
                    try copy(fh, size, to: target)
                    skip(fh, padded - size)
                    total += size
                    progress?(name, total)
                } else {
                    skip(fh, padded)
                }
            default:
                skip(fh, padded) // directories, symlinks, etc.
            }
        }
    }

    private static func safeTarget(_ dest: URL, _ entryName: String, _ stripTopDir: Bool) throws -> URL? {
        var name = entryName.replacingOccurrences(of: "\\", with: "/")
        while name.hasPrefix("/") { name.removeFirst() }
        if name.isEmpty || name.hasSuffix("/") { return nil }
        if stripTopDir {
            guard let slash = name.firstIndex(of: "/") else { return nil } // top-level file; with stripTopDir we expect everything under one dir
            name = String(name[name.index(after: slash)...])
            if name.isEmpty { return nil }
        }
        if name.split(separator: "/").contains(where: { $0 == ".." }) { throw VoiceError(.PACK_CORRUPT, "Unsafe path in archive: \(entryName)") }
        let f = dest.appendingPathComponent(name)
        let destPath = dest.standardizedFileURL.path
        let filePath = f.standardizedFileURL.path
        if !(filePath.hasPrefix(destPath + "/")) { throw VoiceError(.PACK_CORRUPT, "Path escapes destination: \(entryName)") }
        return f
    }

    private static func copy(_ fh: FileHandle, _ size: Int64, to target: URL) throws {
        let fm = FileManager.default
        try? fm.removeItem(at: target)
        guard fm.createFile(atPath: target.path, contents: nil, attributes: nil), let out = FileHandle(forWritingAtPath: target.path) else {
            throw VoiceError(.PACK_CORRUPT, "cannot create \(target.lastPathComponent)")
        }
        defer { out.closeFile() }
        var remaining = size
        while remaining > 0 {
            let chunk = fh.readData(ofLength: Int(min(remaining, 1 << 20)))
            if chunk.isEmpty { throw VoiceError(.PACK_CORRUPT, "archive truncated at \(target.lastPathComponent)") }
            out.write(chunk)
            remaining -= Int64(chunk.count)
        }
    }

    private static func skip(_ fh: FileHandle, _ n: Int64) {
        if n <= 0 { return }
        fh.seek(toFileOffset: fh.offsetInFile + UInt64(n))
    }

    private static func readString(_ fh: FileHandle, _ n: Int64) -> String {
        if n <= 0 { return "" }
        let data = fh.readData(ofLength: Int(n))
        return cstr([UInt8](data), 0, data.count)
    }

    private static func parsePax(_ text: String) -> [String: String] {
        var m: [String: String] = [:]
        for line in text.split(separator: "\n") {
            guard let sp = line.firstIndex(of: " ") else { continue }
            let rec = line[line.index(after: sp)...]
            guard let eq = rec.firstIndex(of: "=") else { continue }
            m[String(rec[..<eq])] = String(rec[rec.index(after: eq)...])
        }
        return m
    }

    private static func cstr(_ b: [UInt8], _ off: Int, _ len: Int) -> String {
        var end = off
        let limit = min(off + len, b.count)
        while end < limit && b[end] != 0 { end += 1 }
        if end <= off { return "" }
        return String(decoding: b[off..<end], as: UTF8.self)
    }

    private static func parseOctal(_ b: [UInt8], _ off: Int, _ len: Int) -> Int64 {
        let s = cstr(b, off, len).trimmingCharacters(in: CharacterSet(charactersIn: " \0"))
        if s.isEmpty { return 0 }
        return Int64(s, radix: 8) ?? 0
    }
}

// MARK: - Pack manager

/// Downloads, verifies and locates tier-2 model packs under `<storageDir>/<packId>/`. A directory that already holds
/// the files the catalog's `model` block refers to counts as installed.
final class PackManager {
    let storageDir: URL
    private(set) var catalogUrl: String
    private var catalog: [String: PackSpec]
    private var order: [String]
    private var downloading = Set<String>()
    private let lock = NSLock()

    typealias Progress = (_ packId: String, _ phase: String, _ bytes: Int64, _ total: Int64) -> Void

    init(storageDir: URL, inlineCatalog: [PackSpec], catalogUrl: String = "") {
        self.storageDir = storageDir
        self.catalogUrl = catalogUrl
        var m: [String: PackSpec] = [:]
        var o: [String] = []
        for p in inlineCatalog {
            if m[p.id] == nil { o.append(p.id) }
            m[p.id] = p
        }
        catalog = m
        order = o
        try? FileManager.default.createDirectory(at: storageDir, withIntermediateDirectories: true, attributes: nil)
        // Model packs are re-downloadable: keep them out of iCloud/iTunes backups.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var dir = storageDir
        try? dir.setResourceValues(values)
    }

    func setCatalogUrl(_ url: String) { lock.lock(); catalogUrl = url; lock.unlock() }

    func catalogList() -> [PackSpec] {
        lock.lock(); defer { lock.unlock() }
        return order.compactMap { catalog[$0] }
    }

    func spec(_ id: String) -> PackSpec? {
        lock.lock(); defer { lock.unlock() }
        return catalog[id]
    }

    func dir(for id: String) -> URL { return storageDir.appendingPathComponent(id, isDirectory: true) }

    /// Pulls the remote catalog (if configured) and merges it over the inline one. Never throws.
    @discardableResult
    func refreshCatalog() -> Bool {
        lock.lock(); let url = catalogUrl; lock.unlock()
        if url.isEmpty { return false }
        do {
            let text = try Http.getText(url)
            let remote = try PackCatalog.parse(text.data(using: .utf8) ?? Data())
            lock.lock()
            for p in remote {
                if catalog[p.id] == nil { order.append(p.id) }
                catalog[p.id] = p
            }
            lock.unlock()
            let cache = storageDir.appendingPathComponent(".catalog.cache.json")
            try? Json.stringify(["packs": remote.map { $0.raw }]).write(to: cache, atomically: true, encoding: .utf8)
            return true
        } catch {
            IVLog.warn("catalog refresh failed: \(error)")
            return false
        }
    }

    /// Files the engine needs, resolved from the pack's `model` block.
    func requiredFiles(_ spec: PackSpec) -> [URL] {
        let d = dir(for: spec.id)
        var names: [String] = []
        func collect(_ v: Any?) {
            if let s = v as? String {
                if !s.isEmpty && !s.hasPrefix("#") {
                    for part in s.split(separator: ",") {
                        let t = part.trimmingCharacters(in: .whitespaces)
                        if !t.isEmpty { names.append(t) }
                    }
                }
            } else if let m = v as? [String: Any] {
                for (_, x) in m { collect(x) }
            } else if let a = v as? [Any] {
                for x in a { collect(x) }
            }
        }
        collect(spec.model)
        if names.isEmpty && !spec.files.isEmpty { names = spec.files.map { $0.path } }
        return names.map { d.appendingPathComponent($0) }
    }

    func isInstalled(_ id: String) -> Bool {
        guard let spec = self.spec(id) else { return false }
        let req = requiredFiles(spec)
        let fm = FileManager.default
        if req.isEmpty { return fm.fileExists(atPath: dir(for: id).appendingPathComponent(".pack.json").path) }
        for f in req {
            var isDir: ObjCBool = false
            if !fm.fileExists(atPath: f.path, isDirectory: &isDir) { return false }
            if !isDir.boolValue {
                let size = (try? fm.attributesOfItem(atPath: f.path)[.size] as? NSNumber)?.int64Value ?? 0
                if size <= 0 { return false }
            }
        }
        return true
    }

    func status(_ id: String) -> [String: Any] {
        let s = spec(id)
        lock.lock(); let dl = downloading.contains(id); lock.unlock()
        return [
            "id": id,
            "known": s != nil,
            "installed": isInstalled(id),
            "downloading": dl,
            "dir": dir(for: id).path,
            "spec": jsonOrNull(s?.toJson()),
        ]
    }

    func listStatus() -> [[String: Any]] {
        return catalogList().map { status($0.id) }
    }

    /// Installs a pack (idempotent). Blocks; call from a background queue.
    @discardableResult
    func install(_ id: String, progress: Progress?) throws -> URL {
        guard let spec = self.spec(id) else { throw VoiceError(.PACK_MISSING, "Unknown pack '\(id)'") }
        let d = dir(for: id)
        if isInstalled(id) { return d }
        lock.lock()
        if downloading.contains(id) { lock.unlock(); throw VoiceError(.PACK_MISSING, "Pack '\(id)' is already downloading") }
        downloading.insert(id)
        lock.unlock()
        defer { lock.lock(); downloading.remove(id); lock.unlock() }

        let fm = FileManager.default
        let tmp = storageDir.appendingPathComponent(".tmp", isDirectory: true)
        try fm.createDirectory(at: tmp, withIntermediateDirectories: true, attributes: nil)
        if let arch = spec.archive, !arch.url.isEmpty {
            let ext: String
            switch arch.format {
            case "tar.bz2": ext = "tar.bz2"
            case "tar.gz": ext = "tar.gz"
            case "zip": ext = "zip"
            case "tar": ext = "tar"
            default: ext = "bin"
            }
            let file = tmp.appendingPathComponent("\(id).\(ext)")
            IVLog.info("downloading pack \(id) from \(arch.url)")
            try Http.download(arch.url, to: file, expectedSha256: arch.sha256) { b, t in
                progress?(id, "download", b, t > 0 ? t : spec.sizeBytes)
            }
            if arch.format == "file" {
                try fm.createDirectory(at: d, withIntermediateDirectories: true, attributes: nil)
                let firstModelFile = spec.model.values.compactMap { $0 as? String }.first
                let targetName = spec.files.first?.path ?? firstModelFile ?? file.lastPathComponent
                let target = d.appendingPathComponent(targetName)
                try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: nil)
                try? fm.removeItem(at: target)
                try fm.moveItem(at: file, to: target)
            } else {
                let staging = tmp.appendingPathComponent("\(id).extract", isDirectory: true)
                try? fm.removeItem(at: staging)
                try Archives.extract(archive: file, format: arch.format, dest: staging, stripTopDir: arch.stripTopDir) { _, bytes in
                    progress?(id, "extract", bytes, 0)
                }
                try? fm.removeItem(at: d)
                try fm.moveItem(at: staging, to: d)
                try? fm.removeItem(at: file)
            }
        }
        for f in spec.files {
            let target = d.appendingPathComponent(f.path)
            if fm.fileExists(atPath: target.path) {
                let size = (try? fm.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value ?? 0
                if size > 0 {
                    if let sha = f.sha256, !sha.isEmpty {
                        if (try? Http.sha256(of: target))?.lowercased() == sha.lowercased() { continue }
                    } else {
                        continue
                    }
                }
            }
            IVLog.info("downloading \(f.path) for pack \(id)")
            try Http.download(f.url, to: target, expectedSha256: f.sha256) { b, t in
                progress?(id, "download:\(f.path)", b, t > 0 ? t : f.sizeBytes)
            }
        }
        let missing = requiredFiles(spec).filter { !fm.fileExists(atPath: $0.path) }
        if !missing.isEmpty {
            throw VoiceError(.PACK_CORRUPT, "Pack '\(id)' is missing files: " + missing.map { $0.lastPathComponent }.joined(separator: ", "))
        }
        try fm.createDirectory(at: d, withIntermediateDirectories: true, attributes: nil)
        let marker: [String: Any] = ["id": id, "version": spec.version, "installedAt": nowMs(), "engine": spec.engine]
        try? Json.stringify(marker, pretty: true).write(to: d.appendingPathComponent(".pack.json"), atomically: true, encoding: .utf8)
        progress?(id, "done", 1, 1)
        return d
    }

    func delete(_ id: String) -> Bool {
        let d = dir(for: id)
        if !FileManager.default.fileExists(atPath: d.path) { return false }
        do { try FileManager.default.removeItem(at: d); return true } catch { return false }
    }
}

// MARK: - Engine protocols

protocol SttEngine: AnyObject {
    var id: String { get }
    var tier: Tier { get }
    func availability(_ language: Language?) -> Availability
    func transcribe(_ audio: PcmAudio, options: SttOptions) throws -> TranscriptResult
    func close()
}

protocol TtsEngine: AnyObject {
    var id: String { get }
    var tier: Tier { get }
    func availability(_ language: Language) -> Availability
    func voices(_ language: Language?) -> [VoiceInfo]
    func synthesize(_ options: TtsOptions) throws -> TtsResult
    func close()
}

/// Tier-1 engines that listen to the microphone themselves (Apple Speech does its own endpointing + partials).
protocol LiveCapable: AnyObject {
    func startLive(options: SttOptions, listener: LiveSttListener) throws -> LiveSttSession
}

protocol LiveSttSession: AnyObject {
    var tier: Tier { get }
    /// Stop listening; a final result (if any) is still delivered.
    func stop()
    /// Abort without a final result.
    func cancel()
}

/// Callbacks of a live session. Closures instead of a protocol so the module can wire them straight to sendEvent.
final class LiveSttListener {
    var onReady: () -> Void = {}
    var onSpeechStart: () -> Void = {}
    var onSpeechEnd: () -> Void = {}
    var onLevel: (Double) -> Void = { _ in }
    var onPartial: (String, DetectedLanguage?) -> Void = { _, _ in }
    var onFinal: (TranscriptResult) -> Void = { _ in }
    var onTierChanged: (Tier, String) -> Void = { _, _ in }
    var onError: (VoiceError) -> Void = { _ in }
    var onStopped: () -> Void = {}
}

/// Frame-level voice activity detection at 16 kHz.
protocol Vad: AnyObject {
    var frameSize: Int { get }
    var name: String { get }
    func isSpeech(_ frame: [Int16]) -> Bool
    func reset()
}

/// Dependency-free energy VAD, used when the silero pack is not installed.
final class EnergyVad: Vad {
    let name = "energy"
    let frameSize: Int
    private let thresholdDb: Double
    private var noiseFloor = -60.0

    init(thresholdDb: Double = -42.0, frameSize: Int = 480) {
        self.thresholdDb = thresholdDb
        self.frameSize = frameSize
    }

    func isSpeech(_ frame: [Int16]) -> Bool {
        if frame.isEmpty { return false }
        var acc = 0.0
        for s in frame {
            let f = Double(s) / 32768
            acc += f * f
        }
        let db = acc <= 0 ? -120.0 : 10 * log10(acc / Double(frame.count))
        // Adaptive floor: slowly track quiet frames.
        if db < noiseFloor + 6 { noiseFloor = noiseFloor * 0.98 + db * 0.02 }
        return db > max(thresholdDb, noiseFloor + 12)
    }

    func reset() { noiseFloor = -60 }
}

/// Turns a stream of frames into speech segments using a Vad. Used for live recognition on tiers 2 and 3 and for
/// recordUtterance (tier 1 engines do their own endpointing).
final class Segmenter {
    private let vad: Vad
    private let sampleRate: Int
    private let endpointSilenceMs: Int
    private let maxUtteranceMs: Int
    private let minSpeechMs: Int
    private let onSpeechStart: () -> Void
    private let onSegment: (PcmAudio) -> Void
    private var pending: [Int16] = []
    private var buffer: [Int16] = []
    private var preRoll: [Int16] = []
    private var inSpeech = false
    private var silenceMs = 0
    private var speechMs = 0
    private let frameMs: Int
    private let maxPreRollSamples: Int
    private let lock = NSLock()

    init(vad: Vad, sampleRate: Int, endpointSilenceMs: Int, maxUtteranceMs: Int, minSpeechMs: Int, paddingMs: Int = 200,
         onSpeechStart: @escaping () -> Void, onSegment: @escaping (PcmAudio) -> Void) {
        self.vad = vad
        self.sampleRate = sampleRate
        self.endpointSilenceMs = endpointSilenceMs
        self.maxUtteranceMs = maxUtteranceMs
        self.minSpeechMs = minSpeechMs
        self.onSpeechStart = onSpeechStart
        self.onSegment = onSegment
        self.frameMs = max(1, vad.frameSize * 1000 / sampleRate)
        self.maxPreRollSamples = sampleRate * paddingMs / 1000
    }

    func accept(_ samples: [Int16]) {
        lock.lock(); defer { lock.unlock() }
        pending.append(contentsOf: samples)
        var offset = 0
        let n = vad.frameSize
        while pending.count - offset >= n {
            let frame = Array(pending[offset..<(offset + n)])
            offset += n
            process(frame)
        }
        if offset > 0 { pending.removeFirst(offset) }
    }

    private func process(_ frame: [Int16]) {
        let speech = vad.isSpeech(frame)
        if !inSpeech {
            preRoll.append(contentsOf: frame)
            if preRoll.count > maxPreRollSamples { preRoll.removeFirst(preRoll.count - maxPreRollSamples) }
            if speech {
                inSpeech = true
                silenceMs = 0
                speechMs = frameMs
                buffer = preRoll
                onSpeechStart()
            }
            return
        }
        buffer.append(contentsOf: frame)
        if speech { silenceMs = 0; speechMs += frameMs } else { silenceMs += frameMs }
        let totalMs = buffer.count * 1000 / sampleRate
        if silenceMs >= endpointSilenceMs || totalMs >= maxUtteranceMs {
            inSpeech = false
            let seg = buffer
            buffer = []
            preRoll = []
            if speechMs >= minSpeechMs { onSegment(PcmAudio(samples: seg, sampleRate: sampleRate)) }
            speechMs = 0
            silenceMs = 0
        }
    }

    /// Flush whatever is buffered (called on stop).
    func flush() {
        lock.lock(); defer { lock.unlock() }
        if inSpeech && !buffer.isEmpty && speechMs >= minSpeechMs { onSegment(PcmAudio(samples: buffer, sampleRate: sampleRate)) }
        inSpeech = false
        buffer = []
        preRoll = []
        pending = []
        speechMs = 0
        silenceMs = 0
    }
}

// MARK: - Tier 3: hosted backend

/// Runtime backend settings, injected by the host app (UI or .env); deliberately not part of voice-config.json.
final class BackendSettings {
    private let lock = NSLock()
    private var _url = ""
    private var _token = ""
    private var _extraHeaders: [String: String] = [:]

    var url: String {
        get { lock.lock(); defer { lock.unlock() }; return _url }
        set { lock.lock(); _url = newValue; lock.unlock() }
    }
    var token: String {
        get { lock.lock(); defer { lock.unlock() }; return _token }
        set { lock.lock(); _token = newValue; lock.unlock() }
    }
    var extraHeaders: [String: String] {
        get { lock.lock(); defer { lock.unlock() }; return _extraHeaders }
        set { lock.lock(); _extraHeaders = newValue; lock.unlock() }
    }

    func resolvedUrl(_ fromConfig: String) -> String {
        var u = url.isEmpty ? fromConfig : url
        while u.hasSuffix("/") { u.removeLast() }
        return u
    }
}

/// Tier 3 STT over the SDK's neutral contract:
///   POST {backendUrl}{sttPath}  Content-Type: audio/wav (16 kHz mono 16-bit)
///     X-Voice-Language: hi | auto, X-Voice-Keyterms: a,b (optional), Authorization: Bearer <token> (optional)
///   -> 200 application/json TranscriptResult
final class HostedSttEngine: SttEngine {
    let id = "hosted-stt"
    let tier = Tier.hosted
    private let cfg: HostedConfig
    private let backend: BackendSettings
    private let lock = NSLock()
    private var healthCache: (at: Int, value: [String: Any])?

    init(cfg: HostedConfig, backend: BackendSettings) {
        self.cfg = cfg
        self.backend = backend
    }

    private func baseUrl() -> String { return backend.resolvedUrl(cfg.backendUrl) }

    func availability(_ language: Language?) -> Availability {
        if !cfg.enabled { return .no("tier3 disabled in config") }
        let url = baseUrl()
        if url.isEmpty { return .no("no backend URL configured (set it in the app / .env)") }
        if let h = health(url) {
            return Availability(available: true, reason: nil, details: ["url": url, "health": h])
        }
        return Availability(available: true, reason: "backend URL set; health endpoint not reachable (will try anyway)", details: ["url": url])
    }

    /// GET /v1/health, cached for a minute. Returns nil when unreachable.
    func health(_ url: String? = nil, force: Bool = false) -> [String: Any]? {
        let base = url ?? baseUrl()
        let now = nowMs()
        lock.lock()
        let cached = healthCache
        lock.unlock()
        if !force, let c = cached, now - c.at < 60_000 { return c.value }
        do {
            let r = try Http.request("GET", base + "/v1/health", headers: authHeaders(), timeoutMs: 4000)
            if !r.ok { return nil }
            let m = try Json.parseObject(r.body)
            lock.lock(); healthCache = (now, m); lock.unlock()
            return m
        } catch {
            return nil
        }
    }

    private func authHeaders() -> [String: String] {
        var h = backend.extraHeaders
        let token = backend.token
        if !token.isEmpty { h[cfg.authHeader] = cfg.authValuePrefix + token }
        return h
    }

    func transcribe(_ audio: PcmAudio, options: SttOptions) throws -> TranscriptResult {
        let url = baseUrl()
        if url.isEmpty { throw VoiceError(.NO_ENGINE, "No backend URL", tier: tier) }
        let pcm16k = audio.resampled(16000)
        var headers = authHeaders()
        headers["X-Voice-Language"] = options.language?.code ?? LANGUAGE_AUTO
        if !options.keyterms.isEmpty { headers["X-Voice-Keyterms"] = options.keyterms.joined(separator: ",") }
        if !options.allowedLanguages.isEmpty { headers["X-Voice-Allowed-Languages"] = options.allowedLanguages.map { $0.code }.joined(separator: ",") }
        let t0 = nowMs()
        let resp: Http.Response
        do {
            if cfg.sendFormat == "json" {
                let body: [String: Any] = [
                    "audioBase64": pcm16k.toBytesLE().base64EncodedString(),
                    "encoding": "pcm16", "sampleRate": 16000,
                    "language": options.language?.code ?? LANGUAGE_AUTO, "keyterms": options.keyterms,
                ]
                resp = try Http.request("POST", url + cfg.path, body: Json.stringify(body).data(using: .utf8), contentType: "application/json", headers: headers, timeoutMs: cfg.timeoutMs)
            } else {
                resp = try Http.request("POST", url + cfg.path, body: pcm16k.toWavData(), contentType: "audio/wav", headers: headers, timeoutMs: cfg.timeoutMs)
            }
        } catch let e as VoiceError {
            throw VoiceError(e.code, e.code == .TIMEOUT ? "Backend timed out after \(cfg.timeoutMs) ms" : "Backend unreachable: \(e.message)", tier: tier)
        }
        let latency = nowMs() - t0
        if !resp.ok { throw VoiceError(.BACKEND_ERROR, "Backend HTTP \(resp.status): \(String(resp.text.prefix(300)))", tier: tier) }
        let m: [String: Any]
        do { m = try Json.parseObject(resp.body) } catch { throw VoiceError(.BACKEND_ERROR, "Backend returned non-JSON", tier: tier) }
        let r = TranscriptResult.fromJson(m, tierOverride: .hosted, latencyOverride: latency)
        let detected: DetectedLanguage
        if options.language != nil || m.obj("language") == nil {
            // Re-run detection with our hint/allowed set so the output is consistent across tiers.
            detected = LanguageDetector.detect(r.text, engineLang: m.obj("language")?.str("code") ?? m.str("languageCode"), hint: options.language, allowed: options.allowedLanguages)
        } else {
            detected = r.language
        }
        let engineName = m.str("engine") ?? m.str("provider") ?? "backend"
        return r.with(language: detected, audioMs: pcm16k.durationMs, engine: "hosted:" + engineName)
    }

    func close() {}
}

/// Tier 3 TTS:
///   POST {backendUrl}{ttsPath}  application/json {text, language, voiceId, speed, sampleRate, format: "wav"}
///   -> 200 audio/wav (headers X-Voice-Id, X-Voice-Engine)
///   or 200 application/json {audioBase64, sampleRate, voiceId, engine}
final class HostedTtsEngine: TtsEngine {
    let id = "hosted-tts"
    let tier = Tier.hosted
    private let cfg: HostedConfig
    private let backend: BackendSettings

    init(cfg: HostedConfig, backend: BackendSettings) {
        self.cfg = cfg
        self.backend = backend
    }

    private func baseUrl() -> String { return backend.resolvedUrl(cfg.backendUrl) }

    func availability(_ language: Language) -> Availability {
        if !cfg.enabled { return .no("tier3 disabled in config") }
        let url = baseUrl()
        if url.isEmpty { return .no("no backend URL configured") }
        return Availability(available: true, reason: nil, details: ["url": url])
    }

    func voices(_ language: Language?) -> [VoiceInfo] {
        let url = baseUrl()
        if url.isEmpty { return [] }
        do {
            let q = language.map { "?language=\($0.code)" } ?? ""
            let r = try Http.request("GET", url + "/v1/voices" + q, headers: authHeaders(), timeoutMs: 5000)
            if !r.ok { return [] }
            let m = try Json.parseObject(r.body)
            var out: [VoiceInfo] = []
            for v in m.objList("voices") {
                guard let lang = Language.parse(v.str("language")), let vid = v.str("id") else { continue }
                out.append(VoiceInfo(id: vid, language: lang, tier: .hosted, name: v.str("name") ?? vid, gender: v.str("gender"), offline: false, quality: v.str("quality"), installed: true))
            }
            return out
        } catch {
            return []
        }
    }

    private func authHeaders() -> [String: String] {
        var h = backend.extraHeaders
        let token = backend.token
        if !token.isEmpty { h[cfg.authHeader] = cfg.authValuePrefix + token }
        return h
    }

    func synthesize(_ options: TtsOptions) throws -> TtsResult {
        let url = baseUrl()
        if url.isEmpty { throw VoiceError(.NO_ENGINE, "No backend URL", tier: tier) }
        let body: [String: Any] = [
            "text": options.text, "language": options.language.code, "voiceId": options.voiceId ?? "default",
            "speed": options.speed, "sampleRate": options.sampleRate ?? 22050, "format": "wav",
        ]
        let t0 = nowMs()
        let resp: Http.Response
        do {
            resp = try Http.request("POST", url + cfg.path, body: Json.stringify(body).data(using: .utf8), contentType: "application/json", headers: authHeaders(), timeoutMs: cfg.timeoutMs)
        } catch let e as VoiceError {
            throw VoiceError(e.code, e.code == .TIMEOUT ? "Backend timed out" : "Backend unreachable: \(e.message)", tier: tier)
        }
        let latency = nowMs() - t0
        if !resp.ok { throw VoiceError(.BACKEND_ERROR, "Backend HTTP \(resp.status): \(String(resp.text.prefix(300)))", tier: tier) }
        let ct = resp.header("Content-Type") ?? ""
        var voiceId = resp.header("X-Voice-Id") ?? options.voiceId ?? "default"
        var engine = resp.header("X-Voice-Engine") ?? "backend"
        let audio: PcmAudio
        if ct.contains("json") {
            let m = try Json.parseObject(resp.body)
            guard let b64 = m.str("audioBase64"), let bytes = Data(base64Encoded: b64) else { throw VoiceError(.BACKEND_ERROR, "No audio in response", tier: tier) }
            if m.str("format") == "pcm16" {
                audio = PcmAudio.fromBytesLE(bytes, sampleRate: m.int("sampleRate", 22050))
            } else {
                audio = try Wav.decode(bytes)
            }
            voiceId = m.str("voiceId") ?? voiceId
            engine = m.str("engine") ?? engine
        } else {
            audio = try Wav.decode(resp.body)
        }
        return TtsResult(audio: audio, language: options.language, voiceId: voiceId, tier: tier, engine: "hosted:" + engine, latencyMs: latency)
    }

    func close() {}
}
