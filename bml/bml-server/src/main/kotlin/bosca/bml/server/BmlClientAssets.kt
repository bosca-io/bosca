package bosca.bml.server

import bosca.bml.render.HtmlWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wires a page's bundled client JS into its SSR HTML. The compiler plugin emits
 * per-page client TypeScript, the `bmlBundleClient` task bundles it to `<Page>.js`, and the server
 * serves those under [URL_PREFIX] and injects a deferred `<script type="module">` so islands mount
 * after first paint.
 */
object BmlClientAssets {

    /** URL prefix the bundles are served under (namespaced like `/_bml/` to avoid page-route collisions). */
    const val URL_PREFIX = "/_bml/js/"

    /** Maps static, nested, and dynamic module imports to this deployment's asset URLs. */
    fun importMap(moduleUrls: Collection<String>, cacheToken: String): String {
        if (moduleUrls.isEmpty()) return ""
        val imports = moduleUrls.distinct().sorted().associateWith { JsonPrimitive(BmlAssetUrls.versioned(it, cacheToken)) }
        val json = JsonObject(mapOf("imports" to JsonObject(imports))).toString().replace("<", "\\u003c")
        return """<script type="importmap">$json</script>"""
    }

    /** Places the deployment import map before authored and generated module scripts. */
    fun injectImportMap(html: String, importMap: String): String {
        if (importMap.isEmpty()) return html
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        return if (head == null) "$importMap$html"
        else html.substring(0, head.range.last + 1) + importMap + html.substring(head.range.last + 1)
    }

    /** The `<script>` tag that loads a page's client bundle. `type="module"` defers automatically. */
    fun scriptTag(module: String): String = scriptTag(module, null)

    fun scriptTag(module: String, cacheToken: String?): String =
        """<script type="module" src="${BmlAssetUrls.versioned("$URL_PREFIX$module", cacheToken)}"></script>"""

    /** Insert the bundle's script tag just before `</body>` (or append if there is no body). */
    fun inject(html: String, module: String): String = inject(html, module, null)

    fun inject(html: String, module: String, cacheToken: String?): String =
        injectUrl(html, "$URL_PREFIX$module", cacheToken)

    /** Insert a deferred module `<script>` for an arbitrary [srcUrl] (e.g. the global `app.js`) before `</body>`. */
    fun injectUrl(html: String, srcUrl: String): String = injectUrl(html, srcUrl, null)

    fun injectUrl(html: String, srcUrl: String, cacheToken: String?): String {
        val tag = """<script type="module" src="${BmlAssetUrls.versioned(srcUrl, cacheToken)}"></script>"""
        return injectTag(html, tag)
    }

    /** Insert the route and concrete render path used to authorize deferred requests. */
    fun injectPageContext(html: String, route: String, path: String): String =
        injectPageContext(html, route, path, null)

    /** Insert the route, concrete render path, and server-selected locale used by deferred requests. */
    fun injectPageContext(html: String, route: String, path: String, locale: String?): String {
        val writer = HtmlWriter()
        writer.markup("<script type=\"application/json\"")
        writer.attr("data-bml-page", route)
        writer.markup(" data-bml-page-canonical")
        writer.attr("data-bml-page-path", path)
        locale?.let { writer.attr("data-bml-page-locale", it) }
        writer.markup("></script>")
        return injectTag(html, writer.toString())
    }

    /** Tell deferred clients that the server can establish a missing installation identity. */
    fun injectInstallationIdentity(html: String): String =
        injectTag(html, "<script type=\"application/json\" data-bml-installation-identity></script>")

    /** Mark an anonymous shared shell so its client runtime establishes identity before private requests. */
    fun injectSharedIdentity(html: String, installationIdentity: Boolean): String {
        val installation = if (installationIdentity) " data-bml-installation-identity" else ""
        return injectTag(
            html,
            "<script type=\"application/json\" data-bml-shared-identity$installation></script>",
        )
    }

    private fun injectTag(html: String, tag: String): String {
        val idx = html.lastIndexOf("</body>")
        return if (idx >= 0) html.substring(0, idx) + tag + "\n" + html.substring(idx) else "$html\n$tag"
    }
}
