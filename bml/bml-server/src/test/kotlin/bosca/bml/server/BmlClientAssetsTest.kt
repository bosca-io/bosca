package bosca.bml.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BmlClientAssetsTest {

    @Test
    fun `deployment import maps version every module deterministically and escape script content`() {
        val urls = listOf("/_bml/js/chunks/shared.js", "/_bml/libraries/key/lazy.js", "/_bml/js/chunks/</script>.js")
        val first = BmlClientAssets.importMap(urls + urls.first(), "deploy one")
        assertEquals(first, BmlClientAssets.importMap(urls.reversed(), "deploy one"))
        assertFalse(first.substringBeforeLast("</script>").contains("</script>"))
        for (token in listOf("deploy one", "deploy two")) {
            val tag = BmlClientAssets.importMap(urls, token)
            val imports = Json.parseToJsonElement(tag.substringAfter('>').substringBeforeLast("</script>")).jsonObject
                .getValue("imports").jsonObject
            assertEquals(urls.toSet(), imports.keys)
            urls.forEach { assertEquals("$it?_ts=${token.replace(" ", "%20")}", imports.getValue(it).jsonPrimitive.content) }
        }
        assertEquals("", BmlClientAssets.importMap(emptyList(), "deploy"))
    }

    @Test
    fun `import map precedes authored module scripts with and without an explicit head`() {
        val map = BmlClientAssets.importMap(listOf("/_bml/js/chunks/shared.js"), "deploy")
        for (html in listOf(
            "<html><HEAD lang=\"en\"><script type=\"module\" src=\"/early.js\"></script></HEAD></html>",
            "<script type=\"module\" src=\"/early.js\"></script>",
        )) {
            val rendered = BmlClientAssets.injectImportMap(html, map)
            assertTrue(rendered.indexOf("type=\"importmap\"") < rendered.indexOf("type=\"module\""), rendered)
            assertEquals(html, BmlClientAssets.injectImportMap(html, ""))
        }
    }

    @Test
    fun `inject page context escapes and places its marker before body close`() {
        assertEquals(
            """<html><body>x<script type="application/json" data-bml-page="/items/&quot;quoted&quot;" data-bml-page-canonical data-bml-page-path="/render/&quot;quoted&quot;" data-bml-page-locale="en-&quot;quoted&quot;"></script>
</body></html>""",
            BmlClientAssets.injectPageContext(
                "<html><body>x</body></html>",
                "/items/\"quoted\"",
                "/render/\"quoted\"",
                "en-\"quoted\"",
            ),
        )
    }

    @Test
    fun `inject installation identity capability before body close`() {
        assertEquals(
            """<html><body>x<script type="application/json" data-bml-installation-identity></script>
</body></html>""",
            BmlClientAssets.injectInstallationIdentity("<html><body>x</body></html>"),
        )
    }

    @Test
    fun `inject shared identity marker with optional installation capability`() {
        assertEquals(
            """<html><body>x<script type="application/json" data-bml-shared-identity></script>
</body></html>""",
            BmlClientAssets.injectSharedIdentity("<html><body>x</body></html>", installationIdentity = false),
        )
        assertEquals(
            """<html><body>x<script type="application/json" data-bml-shared-identity data-bml-installation-identity></script>
</body></html>""",
            BmlClientAssets.injectSharedIdentity("<html><body>x</body></html>", installationIdentity = true),
        )
    }

    @Test
    fun `script tag points at the bundle under the js prefix`() {
        assertEquals(
            """<script type="module" src="/_bml/js/HomePage.js"></script>""",
            BmlClientAssets.scriptTag("HomePage.js"),
        )
    }

    @Test
    fun `inject inserts the script just before closing body`() {
        val html = "<html><body><h1>hi</h1></body></html>"
        val out = BmlClientAssets.inject(html, "HomePage.js")
        assertTrue(out.startsWith("<html><body><h1>hi</h1>"), out)
        assertTrue(
            out.indexOf("/_bml/js/HomePage.js") in 0 until out.indexOf("</body>"),
            "script must precede </body>:\n$out",
        )
    }

    @Test
    fun `inject appends when there is no body`() {
        val out = BmlClientAssets.inject("<div>x</div>", "A.js")
        assertTrue(out.contains("/_bml/js/A.js"), out)
    }

    @Test
    fun `generated script URLs carry the encoded deployment cache token`() {
        assertEquals(
            """<script type="module" src="/_bml/js/HomePage.js?_ts=release%2042"></script>""",
            BmlClientAssets.scriptTag("HomePage.js", "release 42"),
        )
    }
}
