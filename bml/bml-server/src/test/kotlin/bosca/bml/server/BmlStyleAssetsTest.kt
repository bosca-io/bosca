package bosca.bml.server

import bosca.bml.render.BmlComponentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlStyleAssetsTest {

    private fun info(tag: String, styles: String = "x{}", deps: List<String> = emptyList()) =
        BmlComponentInfo(tag, scope = tag, styles = styles, deps = deps)

    @Test
    fun `closure expands transitively and dedupes`() {
        val byTag = listOf(
            info("page-card", deps = listOf("badge", "avatar")),
            info("badge"),
            info("avatar", deps = listOf("badge")), // badge reachable two ways
        ).associateBy { it.tag }
        assertEquals(listOf("avatar", "badge", "page-card"), BmlStyleAssets.closure(listOf("page-card"), byTag))
    }

    @Test
    fun `closure tolerates unknown tags and cycles`() {
        val byTag = listOf(
            info("a", deps = listOf("b")),
            info("b", deps = listOf("a", "ghost")), // cycle a<->b, plus an unknown dep
        ).associateBy { it.tag }
        assertEquals(listOf("a", "b", "ghost"), BmlStyleAssets.closure(listOf("a"), byTag))
    }

    @Test
    fun `eager closure excludes dependencies used only by deferred bodies`() {
        val byTag = listOf(
            BmlComponentInfo(
                tag = "shell",
                scope = null,
                styles = "",
                deps = listOf("public-card", "private-card"),
                eagerDeps = listOf("public-card"),
            ),
            info("public-card"),
            info("private-card"),
        ).associateBy { it.tag }

        assertEquals(listOf("public-card", "shell"), BmlStyleAssets.eagerClosure(listOf("shell"), byTag))
        assertEquals(listOf("private-card", "public-card", "shell"), BmlStyleAssets.closure(listOf("shell"), byTag))
    }

    @Test
    fun `cssLinks emits global first then one link per component with styles`() {
        val byTag = listOf(
            info("badge", styles = ".badge{}"),
            info("plain", styles = ""), // no scoped CSS -> no link
        ).associateBy { it.tag }
        val links = BmlStyleAssets.cssLinks(hasGlobalCss = true, listOf("badge", "plain"), byTag)
        assertTrue(links.indexOf("/_bml/app.css") < links.indexOf("/_bml/css/badge.css"), "global must come first: $links")
        assertTrue("""<link rel="stylesheet" href="/_bml/css/badge.css">""" in links, links)
        assertFalse("plain.css" in links, "a component without scoped CSS must not be linked: $links")
    }

    @Test
    fun `cssLinks omits the global link when there is none`() {
        val byTag = mapOf("badge" to info("badge", styles = ".badge{}"))
        val links = BmlStyleAssets.cssLinks(hasGlobalCss = false, listOf("badge"), byTag)
        assertFalse("/_bml/app.css" in links, links)
        assertTrue("/_bml/css/badge.css" in links, links)
    }

    @Test
    fun `cssLinks appends one encoded deployment cache token to every generated asset`() {
        val byTag = mapOf("badge" to info("badge", styles = ".badge{}"))
        val links = BmlStyleAssets.cssLinks(true, listOf("badge"), byTag, "release 42")
        assertEquals(2, "_ts=release%2042".toRegex().findAll(links).count(), links)
        assertTrue("/_bml/app.css?_ts=release%2042" in links, links)
        assertTrue("/_bml/css/badge.css?_ts=release%2042" in links, links)
    }

    @Test
    fun `injectHead inserts before closing head`() {
        val out = BmlStyleAssets.injectHead("<html><head><title>t</title></head><body>x</body></html>", "<link>")
        assertTrue(out.indexOf("<link>") in 0 until out.indexOf("</head>"), out)
    }

    @Test
    fun `injectHead synthesizes a head when the page has only a body`() {
        val out = BmlStyleAssets.injectHead("<html><body>x</body></html>", "<link>")
        assertTrue("<head><link></head>" in out, out)
        assertTrue(out.indexOf("<head>") < out.indexOf("<body>"), out)
    }

    @Test
    fun `injectHead is a no-op for an empty snippet`() {
        val html = "<html><head></head><body>x</body></html>"
        assertEquals(html, BmlStyleAssets.injectHead(html, ""))
    }
}
