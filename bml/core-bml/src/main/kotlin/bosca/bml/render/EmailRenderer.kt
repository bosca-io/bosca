package bosca.bml.render

/**
 * The email render target: makes rendered HTML email-safe — strips
 * `<script>`/island runtime, and **inlines CSS** into element `style` attributes
 * (most email clients ignore `<style>`). v1 supports simple selectors — `tag`,
 * `.class`, `#id`, and `tag.class` (no combinators/pseudo-classes); existing inline
 * styles win on conflict. At-rules (`@media`, `@font-face`, …) cannot be inlined:
 * they are RETAINED as a `<style>` block injected into `<head>` for the clients
 * that honor one. The author supplies table-safe markup.
 */
object EmailRenderer {

    data class CssRule(val selectors: List<String>, val declarations: List<Pair<String, String>>)

    fun render(html: String, css: String = ""): String {
        val (plain, retained) = splitAtRules(css)
        val inlined = inline(stripScripts(html), parseCss(plain))
        return if (retained.isBlank()) inlined else injectStyle(inlined, retained)
    }

    // ── at-rules ─────────────────────────────────────────────────────────────

    /**
     * Split [css] into (plain rules, at-rule blocks). At-rules keep their full text — including
     * nested braces for block at-rules like `@media` — because they re-emit verbatim.
     */
    private fun splitAtRules(css: String): Pair<String, String> {
        if ('@' !in css) return css to ""
        val noComments = css.replace(Regex("(?s)/\\*.*?\\*/"), "")
        val plain = StringBuilder()
        val retained = StringBuilder()
        var i = 0
        while (i < noComments.length) {
            val c = noComments[i]
            if (c == '@') {
                val open = noComments.indexOf('{', i)
                val semi = noComments.indexOf(';', i)
                if (semi in 0 until open || (open < 0 && semi >= 0)) {
                    // Statement at-rule (@import, @charset): up to the semicolon.
                    retained.append(noComments, i, semi + 1).append('\n')
                    i = semi + 1
                } else if (open >= 0) {
                    var depth = 0
                    var j = open
                    while (j < noComments.length) {
                        when (noComments[j]) {
                            '{' -> depth++
                            '}' -> { depth--; if (depth == 0) break }
                        }
                        j++
                    }
                    retained.append(noComments, i, minOf(j + 1, noComments.length)).append('\n')
                    i = j + 1
                } else {
                    i++
                }
            } else {
                plain.append(c)
                i++
            }
        }
        return plain.toString() to retained.toString().trim()
    }

    /** Inject retained at-rules as a `<style>` at the end of `<head>` (or prepend without one). */
    private fun injectStyle(html: String, retained: String): String {
        val style = "<style>$retained</style>"
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        return if (headEnd >= 0) {
            html.substring(0, headEnd) + style + html.substring(headEnd)
        } else {
            style + html
        }
    }

    // ── strip scripts ─────────────────────────────────────────────────────────

    private fun stripScripts(html: String): String =
        Regex("(?is)<script\\b[^>]*>.*?</script>").replace(html, "")

    // ── css parsing ─────────────────────────────────────────────────────────

    fun parseCss(css: String): List<CssRule> {
        val noComments = css.replace(Regex("(?s)/\\*.*?\\*/"), "")
        val rules = mutableListOf<CssRule>()
        var i = 0
        while (i < noComments.length) {
            val open = noComments.indexOf('{', i)
            if (open < 0) break
            val close = noComments.indexOf('}', open)
            if (close < 0) break
            val selectors = noComments.substring(i, open).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val decls = parseDeclarations(noComments.substring(open + 1, close))
            if (selectors.isNotEmpty() && decls.isNotEmpty()) rules.add(CssRule(selectors, decls))
            i = close + 1
        }
        return rules
    }

    private fun parseDeclarations(block: String): List<Pair<String, String>> =
        block.split(';').mapNotNull { d ->
            val colon = d.indexOf(':')
            if (colon < 0) return@mapNotNull null
            val prop = d.substring(0, colon).trim()
            val value = d.substring(colon + 1).trim()
            if (prop.isEmpty() || value.isEmpty()) null else prop to value
        }

    // ── inlining ──────────────────────────────────────────────────────────────

    private fun inline(html: String, rules: List<CssRule>): String {
        if (rules.isEmpty()) return html
        val sb = StringBuilder()
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<' && i + 1 < html.length && html[i + 1].isLetter()) {
                val gt = html.indexOf('>', i)
                if (gt < 0) { sb.append(html.substring(i)); break }
                val selfClose = html[gt - 1] == '/'
                val content = html.substring(i + 1, if (selfClose) gt - 1 else gt)
                sb.append('<').append(applyInline(content, rules)).append(if (selfClose) "/>" else ">")
                i = gt + 1
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun applyInline(tagContent: String, rules: List<CssRule>): String {
        val name = tagContent.takeWhile { it.isLetterOrDigit() }.lowercase()
        if (name.isEmpty()) return tagContent
        val id = attr(tagContent, "id")
        val classes = attr(tagContent, "class")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        val merged = LinkedHashMap<String, String>()
        for (rule in rules) {
            if (rule.selectors.any { matches(it, name, id, classes) }) {
                for ((p, v) in rule.declarations) merged[p] = v
            }
        }
        if (merged.isEmpty()) return tagContent

        // existing inline style wins on conflict
        attr(tagContent, "style")?.let { existing -> for ((p, v) in parseDeclarations(existing)) merged[p] = v }
        val styleValue = merged.entries.joinToString("; ") { "${it.key}: ${it.value}" }

        val withoutStyle = tagContent.replace(Regex("(?i)\\s+style\\s*=\\s*\"[^\"]*\""), "")
        return "$withoutStyle style=\"$styleValue\""
    }

    private fun matches(selector: String, tag: String, id: String?, classes: Set<String>): Boolean {
        var rest = selector.trim()
        if (rest.isEmpty()) return false
        var selTag: String? = null
        if (rest[0].isLetter()) {
            selTag = rest.takeWhile { it.isLetterOrDigit() }.lowercase()
            rest = rest.drop(selTag.length)
        }
        var selId: String? = null
        val selClasses = mutableListOf<String>()
        var i = 0
        while (i < rest.length) {
            when (rest[i]) {
                '.' -> { val t = rest.drop(i + 1).takeWhile { it == '-' || it == '_' || it.isLetterOrDigit() }; selClasses.add(t); i += t.length + 1 }
                '#' -> { val t = rest.drop(i + 1).takeWhile { it == '-' || it == '_' || it.isLetterOrDigit() }; selId = t; i += t.length + 1 }
                else -> return false // unsupported (combinators, pseudo, attribute selectors)
            }
        }
        if (selTag != null && selTag != tag) return false
        if (selId != null && selId != id) return false
        return selClasses.all { it in classes }
    }

    private fun attr(tagContent: String, name: String): String? =
        Regex("(?i)\\b" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"").find(tagContent)?.groupValues?.get(1)
}
