package bosca.bml.codegen

/**
 * Nuxt/Vue-style scoped-CSS transform. Rewrites a component's `<style scoped>` so its
 * selectors only match elements carrying that component's scope marker
 * (`data-bml-c="<tag>"`), which the codegen stamps onto every element the component
 * renders. The net effect, "friendly like Nuxt": the author writes plain selectors
 * (`.badge`, `.list li`), and BML guarantees the styles can't leak out of the component
 * and page/other-component styles can't leak in.
 *
 * This is a focused transform, not a full CSS parser. It tracks strings, comments, and
 * `()`/`[]` nesting only enough to:
 *   - find rule boundaries (`{ … }`) and the top-level selector list,
 *   - append the scope attribute to the LAST compound of each complex selector, inserted
 *     before any trailing pseudo (`.a:hover` -> `.a[scope]:hover`, `.a .b` -> `.a .b[scope]`),
 *   - recurse into conditional group at-rules (`@media`/`@supports`/`@container`/`@layer { … }`),
 *   - leave name-defining at-rules untouched (`@keyframes`/`@font-face`/`@page`/`@import`/`@charset`),
 *     since scoping their inner selectors (`from`, `0%`) or omitted selectors would be wrong.
 *
 * Declaration blocks are preserved verbatim. Native CSS nesting inside a rule is not descended
 * into (a v1 limitation); `:global()`/`:deep()` escape hatches are a follow-on.
 */
object CssScoper {

    /** Scope [css] to a component, e.g. `scope(".a{…}", "badge")` -> `.a[data-bml-c="badge"]{…}`. */
    fun scope(css: String, scopeValue: String): String =
        scopeRules(css, "[data-bml-c=\"$scopeValue\"]").trim()

    /** Group at-rules whose blocks contain nested style rules that must themselves be scoped. */
    private val GROUP_AT_RULES = setOf("media", "supports", "container", "layer", "scope")

    private fun scopeRules(input: String, attr: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val term = nextTopLevel(input, i)
            if (term.index < 0) {                       // trailing content, no further rule
                val rest = input.substring(i).trim()
                if (rest.isNotEmpty()) out.append(rest)
                break
            }
            if (term.char == ';') {                     // statement at-rule (@import/@charset) — copy verbatim
                out.append(input.substring(i, term.index + 1).trim()).append('\n')
                i = term.index + 1
                continue
            }
            // term.char == '{' — a rule with a block.
            val prelude = input.substring(i, term.index).trim()
            val blockEnd = matchBrace(input, term.index)
            val inner = input.substring(term.index + 1, blockEnd)
            if (prelude.startsWith("@")) {
                val keyword = prelude.drop(1).takeWhile { !it.isWhitespace() && it != '(' }.lowercase()
                val body = if (keyword in GROUP_AT_RULES) scopeRules(inner, attr) else inner.trim()
                out.append(prelude).append(" { ").append(body).append(" }\n")
            } else {
                out.append(scopeSelectorList(prelude, attr)).append(" { ").append(inner.trim()).append(" }\n")
            }
            i = blockEnd + 1
        }
        return out.toString()
    }

    private fun scopeSelectorList(selectors: String, attr: String): String =
        splitTopLevel(selectors, ',').joinToString(", ") { scopeComplexSelector(it.trim(), attr) }

    /** Append the scope attribute to the final compound selector (before any trailing pseudo). */
    private fun scopeComplexSelector(selector: String, attr: String): String {
        if (selector.isEmpty()) return selector
        var paren = 0
        var bracket = 0
        var lastCompoundStart = 0
        var k = 0
        while (k < selector.length) {
            when (val c = selector[k]) {
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                ' ', '\t', '\n', '\r', '>', '+', '~' -> if (paren == 0 && bracket == 0) lastCompoundStart = k + 1
                else -> {}
            }
            k++
        }
        val prefix = selector.substring(0, lastCompoundStart)
        val lastCompound = selector.substring(lastCompoundStart)
        return prefix + insertScopeIntoCompound(lastCompound, attr)
    }

    /** Insert [attr] before the first pseudo (`:`) in a compound, else append it. */
    private fun insertScopeIntoCompound(compound: String, attr: String): String {
        if (compound.isEmpty()) return attr
        var paren = 0
        var bracket = 0
        var insertAt = compound.length
        for (k in compound.indices) {
            when (val c = compound[k]) {
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                ':' -> if (paren == 0 && bracket == 0) { insertAt = k; break }
                else -> {}
            }
        }
        return compound.substring(0, insertAt) + attr + compound.substring(insertAt)
    }

    // ── boundary scanners (string/comment aware) ────────────────────────────────

    private data class Boundary(val char: Char, val index: Int)

    /** Next top-level `{` or `;` from [start], skipping strings, comments, and `()`/`[]`. */
    private fun nextTopLevel(input: String, start: Int): Boundary {
        var paren = 0
        var bracket = 0
        var i = start
        while (i < input.length) {
            val c = input[i]
            when {
                c == '/' && i + 1 < input.length && input[i + 1] == '*' -> { i = skipComment(input, i); continue }
                c == '"' || c == '\'' -> { i = skipString(input, i); continue }
                c == '(' -> paren++
                c == ')' -> if (paren > 0) paren--
                c == '[' -> bracket++
                c == ']' -> if (bracket > 0) bracket--
                (c == '{' || c == ';') && paren == 0 && bracket == 0 -> return Boundary(c, i)
            }
            i++
        }
        return Boundary(' ', -1)
    }

    /** Index of the `}` matching the `{` at [open], skipping strings, comments, nested braces. */
    private fun matchBrace(input: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < input.length) {
            val c = input[i]
            when {
                c == '/' && i + 1 < input.length && input[i + 1] == '*' -> { i = skipComment(input, i); continue }
                c == '"' || c == '\'' -> { i = skipString(input, i); continue }
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return input.length - 1
    }

    /** Split [s] on top-level [delim], skipping strings, comments, and `()`/`[]`. */
    private fun splitTopLevel(s: String, delim: Char): List<String> {
        val parts = mutableListOf<String>()
        var paren = 0
        var bracket = 0
        var i = 0
        var segStart = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> { i = skipComment(s, i); continue }
                c == '"' || c == '\'' -> { i = skipString(s, i); continue }
                c == '(' -> paren++
                c == ')' -> if (paren > 0) paren--
                c == '[' -> bracket++
                c == ']' -> if (bracket > 0) bracket--
                c == delim && paren == 0 && bracket == 0 -> { parts.add(s.substring(segStart, i)); segStart = i + 1 }
            }
            i++
        }
        parts.add(s.substring(segStart))
        return parts
    }

    /** Returns the index just past a CSS block comment starting at [start]. */
    private fun skipComment(s: String, start: Int): Int {
        var i = start + 2
        while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
        return (i + 2).coerceAtMost(s.length)
    }

    /** Returns the index just past a quoted string starting at [start] (handles `\` escapes). */
    private fun skipString(s: String, start: Int): Int {
        val quote = s[start]
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                quote -> return i + 1
            }
            i++
        }
        return s.length
    }
}
