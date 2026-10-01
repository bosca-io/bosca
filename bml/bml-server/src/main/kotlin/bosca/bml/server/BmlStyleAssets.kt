package bosca.bml.server

import bosca.bml.render.BmlComponentInfo

/**
 * The CSS side of the three-tier asset model (global / per-page / per-component). Pure logic so it can
 * be unit-tested without a server:
 *   - [closure] expands a page's directly-rendered component tags to the transitive set actually on the
 *     page (over each component's `deps`) — "included based on what's rendered",
 *   - [cssLinks] builds the `<head>` `<link>`s for a page: the global stylesheet (if any) then one per
 *     rendered component that has scoped CSS,
 *   - [injectHead] places those links into the page's `<head>`.
 *
 * Per-page CSS stays inline (a page's own `<style>` is emitted in document order); the *component* tier
 * is what's served as cached chunks, since a component is shared across many pages.
 */
object BmlStyleAssets {

    /** The global, every-page stylesheet and script. */
    const val GLOBAL_CSS_URL = "/_bml/app.css"
    const val GLOBAL_JS_URL = "/_bml/app.js"

    /** Per-component stylesheets, e.g. `/_bml/css/badge.css`. */
    const val COMPONENT_CSS_PREFIX = "/_bml/css/"

    fun componentCssUrl(tag: String): String = "$COMPONENT_CSS_PREFIX$tag.css"

    /** Transitive closure of [roots] over each component's deps, in stable (sorted) order. */
    fun closure(roots: List<String>, byTag: Map<String, BmlComponentInfo>): List<String> {
        val seen = linkedSetOf<String>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val tag = stack.removeLast()
            if (!seen.add(tag)) continue
            byTag[tag]?.deps?.forEach { if (it !in seen) stack.addLast(it) }
        }
        return seen.sorted()
    }

    /** Initial-request closure, following only dependencies outside deferred island bodies. */
    fun eagerClosure(roots: List<String>, byTag: Map<String, BmlComponentInfo>): List<String> {
        val seen = linkedSetOf<String>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val tag = stack.removeLast()
            if (!seen.add(tag)) continue
            byTag[tag]?.eagerDeps?.forEach { if (it !in seen) stack.addLast(it) }
        }
        return seen.sorted()
    }

    /**
     * The `<head>` CSS links for a page: the global stylesheet (when [hasGlobalCss]) followed by a link
     * for each component in [componentTagsInClosure] that actually has scoped CSS.
     */
    fun cssLinks(
        hasGlobalCss: Boolean,
        componentTagsInClosure: List<String>,
        byTag: Map<String, BmlComponentInfo>,
    ): String = cssLinks(hasGlobalCss, componentTagsInClosure, byTag, null)

    fun cssLinks(
        hasGlobalCss: Boolean,
        componentTagsInClosure: List<String>,
        byTag: Map<String, BmlComponentInfo>,
        cacheToken: String?,
    ): String = buildString {
        if (hasGlobalCss) append(linkTag(GLOBAL_CSS_URL, cacheToken))
        for (tag in componentTagsInClosure) {
            val info = byTag[tag] ?: continue
            if (info.styles.isNotBlank()) append(linkTag(componentCssUrl(tag), cacheToken))
        }
    }

    /**
     * The production `<head>` CSS links: the normalized global stylesheet and, when the
     * page has styles beyond the shared set, its single merged stylesheet — never per-component
     * chunks.
     */
    fun cssLinksProduction(hasGlobalCss: Boolean, pageCssUrl: String?): String =
        cssLinksProduction(hasGlobalCss, pageCssUrl, null)

    fun cssLinksProduction(hasGlobalCss: Boolean, pageCssUrl: String?, cacheToken: String?): String = buildString {
        if (hasGlobalCss) append(linkTag(GLOBAL_CSS_URL, cacheToken))
        pageCssUrl?.let { append(linkTag(it, cacheToken)) }
    }

    private fun linkTag(href: String, cacheToken: String?): String =
        """<link rel="stylesheet" href="${BmlAssetUrls.versioned(href, cacheToken)}">"""

    /** Insert [snippet] into the page's `<head>` (before `</head>`; else synthesize one before `<body>`; else prepend). */
    fun injectHead(html: String, snippet: String): String {
        if (snippet.isEmpty()) return html
        val headClose = html.indexOf("</head>")
        if (headClose >= 0) return html.substring(0, headClose) + snippet + html.substring(headClose)
        val bodyOpen = html.indexOf("<body")
        if (bodyOpen >= 0) return html.substring(0, bodyOpen) + "<head>$snippet</head>" + html.substring(bodyOpen)
        return snippet + html
    }
}
