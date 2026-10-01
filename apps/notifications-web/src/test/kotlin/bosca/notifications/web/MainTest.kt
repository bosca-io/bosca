package bosca.notifications.web

import bosca.bml.render.RenderContext
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {

    @Test
    fun `port defaults to 9094 and honors the first argument`() {
        assertEquals(9094, resolvePort(emptyArray()))
        assertEquals(9094, resolvePort(arrayOf("not-a-port")))
        assertEquals(8081, resolvePort(arrayOf("8081")))
    }

    @Test
    fun `graphql endpoint defaults to the local API`() {
        assertEquals("http://localhost:8080/graphql", graphqlEndpoint())
    }

    @Test
    fun `explicit client dir resolves only when the directory exists`() {
        assertNull(resolveClientDir("/definitely/not/a/dir", development = true))
        val tmp = File(System.getProperty("java.io.tmpdir"))
        assertEquals(tmp, resolveClientDir(tmp.absolutePath, development = false))
    }

    @Test
    fun `default client dir follows the server asset tier`() {
        val projectDirectory =
            File(System.getProperty("java.io.tmpdir"), "notifications-web-${System.nanoTime()}").apply { mkdirs() }
        try {
            val generatedDir = projectDirectory.resolve("build/generated/bml")
            val developmentDir = generatedDir.resolve("js").apply { mkdirs() }
            val productionDir = generatedDir.resolve("js-prod").apply { mkdirs() }

            assertEquals(
                developmentDir,
                resolveClientDir(null, development = true, projectDirectory = projectDirectory),
            )
            assertEquals(
                productionDir,
                resolveClientDir(null, development = false, projectDirectory = projectDirectory),
            )
        } finally {
            projectDirectory.deleteRecursively()
        }
    }

    @Test
    fun `branding has neutral Bosca defaults`() {
        assertEquals(
            Branding(
                name = "Bosca",
                logoUrl = "",
                footerText = "© Bosca",
                primaryColor = "#0e1019",
                accentColor = "#047a52",
            ),
            branding(emptyMap()),
        )
    }

    @Test
    fun `branding reads deployment overrides`() {
        val footerHtml = """<span>Acme notifications</span>
            |<a href="/privacy">Privacy</a>
        """.trimMargin()
        val footerFile = File.createTempFile("notifications-footer-", ".html").apply { writeText(footerHtml) }
        try {
            assertEquals(
                Branding(
                    name = "Acme",
                    logoUrl = "https://cdn.acme.example/logo.svg",
                    footerText = footerHtml,
                    primaryColor = "#123456",
                    accentColor = "#abc",
                ),
                branding(
                    mapOf(
                        "BRAND_NAME" to " Acme ",
                        "BRAND_LOGO_URL" to " https://cdn.acme.example/logo.svg ",
                        "BRAND_FOOTER_HTML_FILE" to " ${footerFile.absolutePath} ",
                        "BRAND_PRIMARY_COLOR" to "#123456",
                        "BRAND_ACCENT_COLOR" to "#abc",
                    ),
                ),
            )
        } finally {
            footerFile.delete()
        }
    }

    @Test
    fun `branding fails when configured footer html file cannot be read`() {
        assertFailsWith<java.io.FileNotFoundException> {
            branding(mapOf("BRAND_FOOTER_HTML_FILE" to "/definitely/not/a/footer.html"))
        }
    }

    @Test
    fun `branding rejects values that could inject CSS`() {
        val branding = branding(
            mapOf(
                "BRAND_PRIMARY_COLOR" to "#123456; } body { display: none",
                "BRAND_ACCENT_COLOR" to "rebeccapurple",
            ),
        )
        assertEquals(DEFAULT_PRIMARY_COLOR, branding.primaryColor)
        assertEquals(DEFAULT_ACCENT_COLOR, branding.accentColor)
    }

    @Test
    fun `css resource is bundled with configured brand colors`() {
        val css = notificationsCss(
            Branding("Acme", "", "Acme notifications", "#112233", "#445566"),
        )
        assertTrue(css.contains("--ink"))
        assertTrue(css.contains("--primary: #112233"))
        assertTrue(css.contains("--accent: #445566"))
    }

    @Test
    fun `site layout renders the configured logo and footer html`() = runBlocking {
        val context = RenderContext()
        val footerHtml = """<span>Acme &amp; partners</span> · <a href="/privacy">Privacy</a>"""
        bml.generated.SiteLayoutComponent.render(
            context,
            mapOf(
                "title" to "Preferences",
                "brand" to "Acme",
                "logo" to "https://cdn.acme.example/logo.svg?size=2&mode=fit",
                "footer" to footerHtml,
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("""class="brand-logo"""" in html, html)
        assertTrue("""alt="Acme"""" in html, html)
        assertTrue("logo.svg?size=2&amp;mode=fit" in html, html)
        assertTrue(footerHtml in html, html)
    }
}
