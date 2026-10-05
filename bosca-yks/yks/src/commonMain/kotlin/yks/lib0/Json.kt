package yks.lib0

/**
 * Minimal JSON stringify/parse for wire compatibility with yjs ContentJSON.
 * Only handles the types used by yjs: null, Boolean, Int, Long, Double, String, List, Map.
 */

fun jsonStringify(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "true" else "false"
    is Int -> value.toString()
    is Long -> value.toString()
    is Float -> value.toDouble().let { d ->
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
    is Double -> if (value == value.toLong().toDouble() && !value.isInfinite()) {
        value.toLong().toString()
    } else {
        value.toString()
    }
    is String -> buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
    is List<*> -> buildString {
        append('[')
        value.forEachIndexed { i, v ->
            if (i > 0) append(',')
            append(jsonStringify(v))
        }
        append(']')
    }
    is Map<*, *> -> buildString {
        append('{')
        var first = true
        for ((k, v) in value) {
            if (!first) append(',')
            first = false
            append(jsonStringify(k.toString()))
            append(':')
            append(jsonStringify(v))
        }
        append('}')
    }
    else -> "null"
}

fun jsonParse(str: String): Any? {
    return JsonParser(str).parse()
}

private class JsonParser(private val str: String) {
    private var pos = 0

    fun parse(): Any? {
        skipWhitespace()
        if (pos >= str.length) return null
        return readValue()
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (pos >= str.length) return null
        return when (str[pos]) {
            '"' -> readString()
            '{' -> readObject()
            '[' -> readArray()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> readNumber()
        }
    }

    private fun readString(): String {
        pos++ // skip opening quote
        val sb = StringBuilder()
        while (pos < str.length && str[pos] != '"') {
            if (str[pos] == '\\') {
                pos++
                when (str[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        pos++
                        val hex = str.substring(pos, pos + 4)
                        sb.append(hex.toInt(16).toChar())
                        pos += 3
                    }
                }
            } else {
                sb.append(str[pos])
            }
            pos++
        }
        if (pos < str.length) pos++ // skip closing quote
        return sb.toString()
    }

    private fun readNumber(): Number {
        val start = pos
        if (pos < str.length && str[pos] == '-') pos++
        while (pos < str.length && str[pos].isDigit()) pos++
        var isFloat = false
        if (pos < str.length && str[pos] == '.') {
            isFloat = true
            pos++
            while (pos < str.length && str[pos].isDigit()) pos++
        }
        if (pos < str.length && (str[pos] == 'e' || str[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < str.length && (str[pos] == '+' || str[pos] == '-')) pos++
            while (pos < str.length && str[pos].isDigit()) pos++
        }
        val numStr = str.substring(start, pos)
        return if (isFloat) {
            numStr.toDouble()
        } else {
            numStr.toIntOrNull() ?: numStr.toLong()
        }
    }

    private fun readObject(): Map<String, Any?> {
        pos++ // skip {
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (pos < str.length && str[pos] == '}') { pos++; return map }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            if (pos < str.length) pos++ // skip :
            val value = readValue()
            map[key] = value
            skipWhitespace()
            if (pos >= str.length || str[pos] == '}') { pos++; break }
            pos++ // skip ,
        }
        return map
    }

    private fun readArray(): List<Any?> {
        pos++ // skip [
        val list = mutableListOf<Any?>()
        skipWhitespace()
        if (pos < str.length && str[pos] == ']') { pos++; return list }
        while (true) {
            list.add(readValue())
            skipWhitespace()
            if (pos >= str.length || str[pos] == ']') { pos++; break }
            pos++ // skip ,
        }
        return list
    }

    private fun skipWhitespace() {
        while (pos < str.length && str[pos].isWhitespace()) pos++
    }

    private fun expect(s: String) {
        for (c in s) {
            if (pos >= str.length || str[pos] != c) {
                throw IllegalStateException("Expected '$s' at position $pos")
            }
            pos++
        }
    }
}
