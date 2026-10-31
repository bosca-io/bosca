package bosca.atlas.ui

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.AfterClass
import org.junit.BeforeClass

/** Exercises shared Atlas routes, packaged assets, and the authenticated HTTP proxy without Git. */
class AtlasUiRoutesTest {
    @Test
    fun `authentication and emailed landing routes are public and Atlas branded`() {
        for (path in listOf("/login", "/auth/signup", "/auth/forgot", "/auth/reset-password?token=test",
            "/auth/verify?token=test", "/auth/link/confirm?proof=test")) {
            val response = get(path)
            assertEquals(200, response.statusCode(), path)
            assertTrue("Bosca Atlas" in response.body(), path)
            assertTrue("class=\"auth-document\"" in response.body(), path)
            assertFalse("Bosca Git" in response.body(), path)
        }
    }

    @Test
    fun `landing route requires a session and links the Atlas applications`() {
        val anonymous = get("/")
        assertEquals(302, anonymous.statusCode())
        assertTrue(anonymous.headers().firstValue("Location").orElse("").startsWith("/login?redirect="))
        val signedIn = get("/", "_atlas_test=atlas-session")
        assertEquals(200, signedIn.statusCode())
        assertTrue("href=\"/repositories\"" in signedIn.body())
        assertTrue("href=\"/artifacts/namespaces\"" in signedIn.body())
        assertTrue("Welcome." in signedIn.body())
        assertTrue("aria-label=\"Atlas systems\"" in signedIn.body())
        assertTrue("Build &amp; Deliver" in signedIn.body())
        assertFalse("data-bml-app-shell" in signedIn.body())
        val light = get("/", "_atlas_test=atlas-session; git_ui_appearance=light")
        assertTrue("data-theme=\"light\"" in light.body())
    }

    @Test
    fun `authentication page bundles and styles are served without a session`() {
        val html = get("/login").body()
        val resources = Regex("(?:src|href)=\"(/_bml/[^\"]+)\"").findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }.toList()
        assertTrue(resources.any { Regex("""/_bml/js/[^/]+\.page-[A-Z0-9]{8}\.js""").matches(it) }, html)
        assertTrue(resources.any { it.substringBefore('?').endsWith(".css") }, html)
        resources.forEach { path ->
            val response = get(path)
            assertEquals(200, response.statusCode(), path)
            assertTrue(response.body().isNotEmpty(), path)
        }
        val javascript = get("/_bml/app.js").body()
        assertTrue("\"authCookiePrefix\":\"_atlas_test\"" in javascript)
        assertTrue("boscaAuth" in javascript)
        assertFalse("gitAuth" in javascript)
    }

    @Test
    fun `same origin proxy forwards the selected cookie and upstream session response`() {
        val response = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/graphql"))
                .header("Cookie", "_atlas_test=atlas-session; _bat=other-session")
                .header("Origin", "http://127.0.0.1:$port")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"query":"query { probe }"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("Bearer atlas-session", authorization)
        assertEquals("http://127.0.0.1:$port", origin)
        assertTrue(response.headers().allValues("Set-Cookie").any { it.startsWith("_atlas_test=refreshed;") })
    }

    private fun get(path: String, cookie: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (cookie != null) request.header("Cookie", cookie)
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val client = HttpClient.newHttpClient()
        private var port = 0
        private lateinit var upstream: HttpServer
        private lateinit var engine: NettyServerEngine
        @Volatile private var authorization: String? = null
        @Volatile private var origin: String? = null

        @JvmStatic
        @BeforeClass
        fun start() {
            upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            upstream.createContext("/graphql") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                origin = exchange.requestHeaders.getFirst("Origin")
                exchange.requestBody.use { it.readAllBytes() }
                val bytes = """{"data":{"probe":true}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Set-Cookie", "_atlas_test=refreshed; Path=/; SameSite=Lax")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            upstream.start()
            val config = ApplicationConfig.load("bosca:\n  server:\n    port: 0\n    drain-timeout-ms: 0\n".byteInputStream())
            val application = BoscaApplication(config)
            AtlasUi.install(application, "http://127.0.0.1:${upstream.address.port}/graphql", "_atlas_test")
            application.freezeMiddleware()
            engine = NettyServerEngine(application, 0)
            thread(isDaemon = true, name = "atlas-ui-test-server") { engine.start() }
            repeat(100) {
                engine.boundPort?.let { port = it; return }
                Thread.sleep(50)
            }
            error("Atlas UI test server did not start")
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
            if (::upstream.isInitialized) upstream.stop(0)
        }
    }
}
