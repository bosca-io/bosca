package bosca.bml.contract

/**
 * Extracts contract declarations from the Kotlin source inside a `<contract>`
 * region: `interface <Name> { [suspend] fun m(p: T, …): R … }`.
 *
 * A focused, depth-aware scanner — it understands generic angle brackets, nested
 * parens, and string literals well enough for interface signatures; full Kotlin
 * parsing is the compiler's job, not this codegen helper's.
 */
object ContractParser {

    fun parse(source: String): List<ContractDecl> {
        val decls = mutableListOf<ContractDecl>()
        var i = 0
        while (true) {
            val kw = indexOfWord(source, "interface", i)
            if (kw < 0) break
            var j = skipWs(source, kw + "interface".length)
            val nameStart = j
            while (j < source.length && isIdentChar(source[j])) j++
            val name = source.substring(nameStart, j)
            val brace = source.indexOf('{', j)
            if (name.isEmpty() || brace < 0) { i = kw + "interface".length; continue }
            val end = matchClose(source, brace, '{', '}')
            val body = source.substring(brace + 1, if (end < 0) source.length else end)
            decls.add(ContractDecl(name, parseFunctions(body)))
            i = if (end < 0) source.length else end + 1
        }
        return decls
    }

    private fun parseFunctions(body: String): List<ContractFunction> {
        val functions = mutableListOf<ContractFunction>()
        var i = 0
        while (true) {
            val f = indexOfWord(body, "fun", i)
            if (f < 0) break
            val isSuspend = wordEndsAt(body, "suspend", f)
            var j = skipWs(body, f + 3)
            val nameStart = j
            while (j < body.length && isIdentChar(body[j])) j++
            val fnName = body.substring(nameStart, j)
            j = skipWs(body, j)
            if (fnName.isEmpty() || j >= body.length || body[j] != '(') { i = f + 3; continue }
            val pEnd = matchClose(body, j, '(', ')')
            if (pEnd < 0) break
            val params = parseParams(body.substring(j + 1, pEnd))
            var k = skipWs(body, pEnd + 1)
            var returnType = "Unit"
            if (k < body.length && body[k] == ':') {
                val rt = readReturnType(body, k + 1)
                returnType = rt.trim()
                k += 1 + rt.length
            }
            functions.add(ContractFunction(fnName, isSuspend, params, returnType))
            i = k
        }
        return functions
    }

    private fun parseParams(params: String): List<ContractParam> =
        splitTopLevel(params).mapNotNull { raw ->
            val p = raw.trim()
            if (p.isEmpty()) return@mapNotNull null
            val colon = topLevelColon(p)
            if (colon < 0) null
            else ContractParam(p.substring(0, colon).trim(), p.substring(colon + 1).trim())
        }

    /** Return type runs to the end of the signature line (interface fns have no body). */
    private fun readReturnType(s: String, from: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' || c == '}') break
            sb.append(c); i++
        }
        return sb.toString()
    }

    // ── scanning helpers ──────────────────────────────────────────────────────

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun indexOfWord(s: String, word: String, from: Int): Int {
        var i = from
        while (true) {
            val idx = s.indexOf(word, i)
            if (idx < 0) return -1
            val before = if (idx == 0) ' ' else s[idx - 1]
            val after = if (idx + word.length >= s.length) ' ' else s[idx + word.length]
            if (!isIdentChar(before) && !isIdentChar(after)) return idx
            i = idx + word.length
        }
    }

    /** True if [word] appears as a whole token immediately before [before] (skipping whitespace). */
    private fun wordEndsAt(s: String, word: String, before: Int): Boolean {
        var i = before - 1
        while (i >= 0 && s[i].isWhitespace()) i--
        if (i < 0) return false
        val end = i + 1
        val start = end - word.length
        if (start < 0) return false
        if (s.substring(start, end) != word) return false
        val pre = if (start == 0) ' ' else s[start - 1]
        return !isIdentChar(pre)
    }

    private fun matchClose(s: String, openIdx: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                open -> depth++
                close -> { depth--; if (depth == 0) return i }
                '"' -> i = skipString(s, i)
            }
            i++
        }
        return -1
    }

    private fun skipString(s: String, openIdx: Int): Int {
        var i = openIdx + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return i
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '<', '(', '[' -> { depth++; sb.append(c) }
                '>', ')', ']' -> { depth--; sb.append(c) }
                ',' -> if (depth == 0) { parts.add(sb.toString()); sb.setLength(0) } else sb.append(c)
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts
    }

    private fun topLevelColon(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }
}
