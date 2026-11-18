package bosca.bml.server

import bosca.bml.library.BmlApplicationAssets
import bosca.bml.library.BmlAssetFile
import bosca.bml.library.BmlClientManifest
import bosca.bml.project.ContentDigest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BmlContentAssetsTest {
    private fun assets(font: String, css: String = "body{font-family:Geist}", namespace: String = "design"): BmlContentAssets {
        val files = mapOf(
            "libraries/$namespace/font.woff2" to font,
            "libraries/$namespace/design.css" to "@font-face{src:url(/_bml/libraries/$namespace/font.woff2)}$css",
            "prod/bml-imports.json" to """{"index.page.js":["index.page-ABCDEFGH.js","chunks/shared-ABCDEFGH.js"]}""",
        ).mapValues { it.value.toByteArray() }
        val manifest = Json.encodeToString(BmlClientManifest.serializer(), BmlClientManifest(files = files.map { (path, bytes) ->
            BmlAssetFile(path, ContentDigest.sha256(bytes))
        })).toByteArray()
        val source = BmlApplicationAssets("app", emptyMap(), emptyMap()) {
            if (it == "app/client-manifest.json") manifest else files[it.removePrefix("app/")]
        }
        return BmlContentAssets(listOf(source), null, mapOf("/_bml/app.js" to "console.log('unchanged')"))
    }

    @Test
    fun `unchanged resources retain URLs and changed dependencies invalidate their stylesheet`() {
        val first = assets("font one")
        val same = assets("font one")
        val changed = assets("font two")
        assertEquals(first.urls, same.urls)
        assertEquals(first.url("/_bml/app.js"), changed.url("/_bml/app.js"))
        for (path in listOf("/_bml/libraries/design/font.woff2", "/_bml/libraries/design/design.css")) {
            assertNotEquals(first.url(path), changed.url(path))
        }
        val stylesheet = changed.bodies.getValue(changed.url("/_bml/libraries/design/design.css"))
        assertTrue(changed.url("/_bml/libraries/design/font.woff2") in stylesheet.decodeToString())
        assertTrue(ContentDigest.sha256(stylesheet).removePrefix("sha256:") in changed.url("/_bml/libraries/design/design.css"))
        assertEquals("index.page-ABCDEFGH.js", changed.entries["index.page.js"])
    }

    @Test
    fun `library version changes preserve identical font and stylesheet URLs`() {
        val first = assets("font one", namespace = "version-one")
        val changedVersion = assets("font one", namespace = "version-two")
        for (file in listOf("font.woff2", "design.css")) {
            assertEquals(first.url("/_bml/libraries/version-one/$file"), changedVersion.url("/_bml/libraries/version-two/$file"))
        }
    }

    @Test
    fun `stylesheet edits preserve font and unrelated script URLs`() {
        val first = assets("font one")
        val changed = assets("font one", "body{color:red}")
        assertEquals(first.packagedPaths, changed.packagedPaths)
        assertEquals(first.url("/_bml/app.js"), changed.url("/_bml/app.js"))
        assertNotEquals(first.url("/_bml/libraries/design/design.css"), changed.url("/_bml/libraries/design/design.css"))
    }
}
