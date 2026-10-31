package dev.indicvoice.core

/**
 * Tiny dependency-free JSON reader/writer.
 *
 * The SDK deliberately has no third-party dependencies (Android apps already
 * fight over library versions, and iOS/KMP ports are easier without them).
 * Values are plain Kotlin: Map<String, Any?>, List<Any?>, String, Double/Long,
 * Boolean and null.
 */
object Json {

    fun parse(text: String): Any? = Parser(text).parseTop()

    fun parseObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (parse(text) as? Map<String, Any?>) ?: throw IllegalArgumentException("JSON root is not an object")
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    // ---------------------------------------------------------------- writer

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Float -> writeNumber(sb, v.toDouble())
            is Double -> writeNumber(sb, v)
            is Number -> sb.append(v.toString())
            is Enum<*> -> quote(sb, v.name)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in list) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    write(sb, item, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), pretty, depth)
            is IntArray -> write(sb, v.toList(), pretty, depth)
            is FloatArray -> write(sb, v.toList(), pretty, depth)
            is DoubleArray -> write(sb, v.toList(), pretty, depth)
            is JsonSerializable -> write(sb, v.toJsonValue(), pretty, depth)
            else -> quote(sb, v.toString())
        }
    }

    private fun writeNumber(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- parser

    private class Parser(private val s: String) {
        private var i = 0

        fun parseTop(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) throw err("Trailing characters")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw err("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw err("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw err("Invalid literal")
            i += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw err("Expected string key")
                val k = parseString()
                skipWs()
                if (peek() != ':') throw err("Expected ':'")
                i++
                m[k] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return m }
                    else -> throw err("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return l }
            while (true) {
                l.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return l }
                    else -> throw err("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw err("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw err("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw err("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw err("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Number {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') { isDouble = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (isDouble) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun err(msg: String) = IllegalArgumentException("$msg at offset $i")
    }
}

/** Implemented by SDK result types so they can be emitted as JSON without reflection. */
interface JsonSerializable {
    fun toJsonValue(): Any?
    fun toJson(pretty: Boolean = false): String = Json.stringify(toJsonValue(), pretty)
}

// ------------------------------------------------------------- map helpers

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>

fun Map<String, Any?>.str(key: String, default: String? = null): String? = (this[key] as? String) ?: default

fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = (this[key] as? Boolean) ?: default

fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default

fun Map<String, Any?>.long(key: String, default: Long): Long = (this[key] as? Number)?.toLong() ?: default

fun Map<String, Any?>.double(key: String, default: Double): Double = (this[key] as? Number)?.toDouble() ?: default

fun Map<String, Any?>.strList(key: String): List<String> = list(key)?.mapNotNull { it as? String } ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> =
    list(key)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.strMap(key: String): Map<String, String> =
    (this[key] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()
