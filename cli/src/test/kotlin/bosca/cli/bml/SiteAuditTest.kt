package bosca.cli.bml

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSink
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives [SiteAudit] against a MockWebServer serving a page with assets. */
class SiteAuditTest {

    private val http = OkHttpClient()

    private fun gzip(text: String): Buffer {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(text) }
        return out
    }

    @Test
    fun `discovers stylesheets, scripts, preloads, and images — not unrelated links`() {
        val html = """
            <html><head>
              <link rel="stylesheet" href="/app.css">
              <link rel="preload" as="font" href="/font.woff2" crossorigin>
              <link rel="modulepreload" href="/island.js">
              <link rel="canonical" href="https://example.com/x">
              <link rel="icon" href="/favicon.ico">
              <script src="/main.js"></script>
              <script>inline()</script>
            </head><body>
              <img src="/hero.png" alt="">
              <img src="data:image/png;base64,AAAA" alt="">
            </body></html>
        """.trimIndent()
        val base = "http://site.example/".toHttpUrl()
        val assets = SiteAudit.discoverAssets(html, base)
        assertEquals(
            listOf(
                "http://site.example/app.css",
                "http://site.example/font.woff2",
                "http://site.example/island.js",
                "http://site.example/main.js",
                "http://site.example/hero.png",
            ),
            assets,
        )
    }

    @Test
    fun `categorizes by content type first, then extension`() {
        assertEquals(SiteAudit.Category.CSS, SiteAudit.categorize("/x", "text/css; charset=utf-8"))
        assertEquals(SiteAudit.Category.JS, SiteAudit.categorize("/x", "application/javascript"))
        assertEquals(SiteAudit.Category.FONT, SiteAudit.categorize("/f.woff2", null))
        assertEquals(SiteAudit.Category.IMAGE, SiteAudit.categorize("/i.webp?v=2", null))
        assertEquals(SiteAudit.Category.OTHER, SiteAudit.categorize("/mystery", null))
    }

    @Test
    fun `audits a route with gzip transfer accounting and css url-ref notes`() {
        val css = ".a{}".repeat(200) + " @font-face { src: url(/fonts/a.woff2) } .b { background: url('/img/bg.png') }"
        val js = "console.log('x');".repeat(100)
        val html = """<html><head><link rel="stylesheet" href="/app.css"><script src="/app.js"></script></head><body>hi</body></html>"""

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .body(gzip(html))
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("Content-Type", "text/html")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .body(gzip(css))
                    .addHeader("Content-Encoding", "gzip")
                    .addHeader("Content-Type", "text/css")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder().body(js).addHeader("Content-Type", "application/javascript").build(),
            )
            server.start()

            val report = SiteAudit(http).audit(server.url("/").toString(), listOf("/"))
            val route = report.routes.single()

            assertEquals(3, route.requestCount)
            val cssRes = route.resources.single { it.category == SiteAudit.Category.CSS }
            assertEquals(css.length.toLong(), cssRes.decodedBytes)
            assertTrue(cssRes.transferBytes < cssRes.decodedBytes, "gzip transfer should be smaller than decoded")
            val jsRes = route.resources.single { it.category == SiteAudit.Category.JS }
            assertEquals(js.length.toLong(), jsRes.transferBytes) // served uncompressed -> transfer == decoded
            assertEquals(js.length.toLong(), jsRes.decodedBytes)
            // The two url(...) refs inside the CSS are counted, not fetched.
            assertEquals(2, route.cssUrlRefs)
            assertEquals(route.resources.sumOf { it.transferBytes }, route.transferBytes)
        }
    }

    @Test
    fun `an unreachable asset is reported as a failed request, not a crash`() {
        val html = """<html><head><script src="http://127.0.0.1:1/dead.js"></script></head><body></body></html>"""
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(html).addHeader("Content-Type", "text/html").build())
            server.start()
            val report = SiteAudit(http).audit(server.url("/").toString(), listOf("/"))
            val dead = report.routes.single().resources.single { it.url.endsWith("dead.js") }
            assertEquals(0, dead.status)
            assertEquals(0, dead.transferBytes)
        }
    }
}
