package bosca.bml.server

import kotlin.test.Test
import kotlin.test.assertEquals

class BmlAssetUrlsTest {
    @Test
    fun `content names preserve extensions and support extensionless resources`() {
        assertEquals("/_bml/content/font-abc.woff2", BmlAssetUrls.content("/_bml/content/font.woff2", "sha256:abc"))
        assertEquals("/with.dot/content/license-abc", BmlAssetUrls.content("/with.dot/content/license", "sha256:abc"))
    }

    @Test
    fun `library CSS versions bundled dependencies and preserves external and inline URLs`() {
        val css = """@font-face { src: url("/_bml/libraries/design/fonts/geist.woff2"); }
            |a { background: url('/_bml/libraries/design/image.svg?size=2#mark'); }
            |b { background: url(data:image/svg+xml;base64,ABC); }
            |c { background: url(https://example.com/image.png); }
        """.trimMargin()
        val expected = css
            .replace("geist.woff2", "geist-abc.woff2")
            .replace("image.svg?size=2#mark", "image-def.svg#mark")
        assertEquals(expected, BmlAssetUrls.libraryCss(css, mapOf("/_bml/libraries/design/fonts/geist.woff2" to "/_bml/libraries/design/fonts/geist-abc.woff2", "/_bml/libraries/design/image.svg" to "/_bml/libraries/design/image-def.svg")))
        assertEquals(css, BmlAssetUrls.libraryCss(css, emptyMap()))
    }
}
