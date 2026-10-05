package bosca.bml.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmlClientAssetsTest {

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
