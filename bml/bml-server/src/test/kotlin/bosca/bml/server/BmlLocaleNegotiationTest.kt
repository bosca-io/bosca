package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.bml.render.currentLocale
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locale negotiation over real HTTP: `?lang` -> `bml_locale` cookie ->
 * `Accept-Language`, constrained to the configured locales, on page renders AND sliver
 * re-renders — plus the ambient [currentLocale] and the `ctx.lang` markup tag.
 */
class BmlLocaleNegotiationTest {

    @Test
    fun `no signals renders the site default`() {
        val body = get("/locale")
        assertTrue("lang=en" in body, "expected the default locale: $body")
    }

    @Test
    fun `accept-language negotiates with q-weights and truncation`() {
        val body = get("/locale", "Accept-Language" to "es-MX;q=0.9, en;q=0.5")
        assertTrue("lang=es" in body && "lang=es-419" !in body, "es-MX should truncate to es: $body")
    }

    @Test
    fun `the bml_locale cookie beats the header`() {
        val body = get("/locale", "Cookie" to "bml_locale=pt-BR", "Accept-Language" to "en")
        assertTrue("lang=pt-BR" in body, "the cookie must win over the header: $body")
    }

    @Test
    fun `the lang query param beats everything`() {
        val body = get("/locale?lang=es-419", "Cookie" to "bml_locale=pt-BR", "Accept-Language" to "en")
        assertTrue("lang=es-419" in body, "?lang must win: $body")
    }

    @Test
    fun `an unsupported lang falls through, never renders unsupported`() {
        val body = get("/locale?lang=de", "Accept-Language" to "es")
        assertTrue("lang=es" in body, "unsupported ?lang should fall through to the header: $body")
    }

    @Test
    fun `the ambient currentLocale sees the negotiated locale`() {
        val body = get("/locale", "Accept-Language" to "es-419")
        assertTrue("ambient=es-419" in body, "currentLocale() must match ctx.locale: $body")
    }

    @Test
    fun `sliver re-renders negotiate from the same request signals`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/render/locale-probe"))
                .header("Content-Type", "application/json")
                .header("Cookie", "bml_locale=es-419")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue("lang=es-419" in res.body(), "sliver ctx must carry the negotiated locale: ${res.body()}")
    }

    @Test
    fun `sliver lang query beats a conflicting cookie`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/render/locale-probe?lang=en"))
                .header("Content-Type", "application/json")
                .header("Cookie", "bml_locale=es-419")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue("lang=en" in res.body(), "sliver ctx must retain the page locale: ${res.body()}")
    }

    @Test
    fun `the i18n endpoint serves the fallback-merged catalog for a locale`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/i18n/es-419.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        val body = res.body()
        assertTrue(""""locale":"es-419"""" in body, body)
        assertTrue(""""home.title":"Bienvenido (LatAm)"""" in body, "nearest locale must win: $body")
        assertTrue(""""en.only":"English only"""" in body, "the chain's tail must fill gaps: $body")
        assertTrue(""""cart.items"""" in body && """"OTHER":"{count} items"""" in body, "plural forms ride along: $body")
    }

    @Test
    fun `an unsupported locale serves the default catalog instead of a 404`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/i18n/de.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue(""""locale":"en"""" in res.body(), "unsupported tags fall to the default: ${res.body()}")
    }

    @Test
    fun `the i18n endpoint revalidates with a body-hash etag`() {
        val first = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/i18n/es.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val etag = first.headers().firstValue("ETag").orElseThrow()
        val second = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/i18n/es.json"))
                .header("If-None-Match", etag).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(304, second.statusCode())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun get(path: String, vararg headers: Pair<String, String>): String {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, res.statusCode())
        return res.body()
    }

    companion object {
        private val client: HttpClient = HttpClient.newHttpClient()
        private var port = 0
        private lateinit var engine: NettyServerEngine

        /** Renders the negotiated locale from both the context and the ambient accessor. */
        private val localePage = object : BmlPageRenderer {
            override val route = "/locale"
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup("<html><body>lang=${ctx.lang} ambient=${currentLocale().toLanguageTag()}</body></html>")
            }
        }

        private val localeProbe = bosca.bml.render.BmlComponentRenderer { ctx, _, _ ->
            ctx.writer.markup("<p>lang=${ctx.lang}</p>")
        }

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            val config = ApplicationConfig.load(
                "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream(),
            )
            val app = BoscaApplication(config)
            BmlServer(
                project = CompiledProject(name = "test", version = "0.0.1"),
                pages = listOf(localePage),
                port = port,
                // The policy normally comes from the site's Bosca localization project; the
                // static form pins the same shape for the test.
                localePolicy = bosca.bml.render.BmlLocalePolicy.static(
                    bosca.bml.render.BmlLocales(listOf("en", "es", "es-419", "pt-BR")),
                ),
                messageSource = bosca.bml.i18n.MessageSource.of(
                    catalogs = mapOf(
                        "en" to bosca.bml.i18n.MessageCatalog(
                            messages = mapOf("home.title" to "Welcome", "en.only" to "English only"),
                            plurals = mapOf(
                                "cart.items" to mapOf(
                                    bosca.bml.i18n.PluralCategory.ONE to "one item",
                                    bosca.bml.i18n.PluralCategory.OTHER to "{count} items",
                                ),
                            ),
                        ),
                        "es" to bosca.bml.i18n.MessageCatalog(messages = mapOf("home.title" to "Bienvenido")),
                        "es-419" to bosca.bml.i18n.MessageCatalog(messages = mapOf("home.title" to "Bienvenido (LatAm)")),
                    ),
                ),
                componentRenderers = mapOf("locale-probe" to localeProbe),
            ).install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-locale-test-server") { engine.start() }
            awaitReady()
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
        }

        private fun awaitReady() {
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(50)
                }
            }
            error("bml-server did not start on port $port")
        }
    }
}
