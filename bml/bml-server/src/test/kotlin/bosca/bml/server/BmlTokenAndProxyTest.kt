package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlIslandActionDispatcher
import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.BmlContractDispatcher
import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.IslandActionResult
import bosca.bml.render.RenderContext
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the request-context plumbing added for real (signed-in) BML sites: the
 * configured auth cookie when resolving the passthrough token, the request cookies + query
 * params exposed on [RenderContext], and the same-origin `/graphql` BFF proxy (body + resolved
 * token forwarded untouched; upstream status/body passed back as-is). Boots a real engine plus a
 * stub upstream GraphQL endpoint plus a separately hosted analytics installation endpoint.
 */
class BmlTokenAndProxyTest {

    @Test
    fun `analytics session reaches upstream through page action fragment and contract requests`() {
        val pathsAndBodies = listOf(
            "/analytics-session" to null,
            "/graphql" to """{"query":"query AnalyticsSessionProbe { session }"}""",
            "/_bml/action" to """{"page":"/whoami","stateKey":"probe","method":"session","state":""}""",
            "/_bml/render/session" to "{}",
            "/_bml/contract/Session/read" to "[]",
        )
        for ((path, body) in pathsAndBodies) {
            for ((session, cookieSession) in listOf("session-a" to null, "session-b" to "older", null to "cookie-session", null to null, " " to "cookie-session", null to "%zz")) {
                val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                    .header("Cookie", "bml_iid=stable-installation" + (cookieSession?.let { "; bml_asid=$it; bml_asid_exp=${System.currentTimeMillis() + 300_000}" } ?: ""))
                    .header("Content-Type", "application/json")
                if (session != null) request.header("X-BA-Session-ID", session)
                if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body))
                val response = client().send(request.build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode(), "$path: ${response.body()}")
                val expected = session?.takeIf(String::isNotBlank) ?: cookieSession?.takeUnless { it == "%zz" }
                val cookies = response.headers().allValues("Set-Cookie")
                if (expected != null) {
                    assertTrue("session=$expected" in response.body(), "$path: ${response.body()}")
                    assertTrue(cookies.none { it.startsWith("bml_asid=") || it.startsWith("bml_asid_exp=") }, cookies.toString())
                } else {
                    val cookie = cookies.single { it.startsWith("bml_asid=") }
                    val resolved = cookie.substringAfter("bml_asid=").substringBefore(';')
                    assertTrue(Regex("[0-7][0-9A-HJKMNP-TV-Z]{25}").matches(resolved), resolved)
                    assertTrue("session=$resolved" in response.body(), "$path: ${response.body()}")
                    assertTrue("Path=/" in cookie && "SameSite=Lax" in cookie, cookie)
                    assertTrue("HttpOnly" !in cookie && "Max-Age" !in cookie, cookie)
                    val expiry = cookies.single { it.startsWith("bml_asid_exp=") }
                    assertTrue(expiry.substringAfter('=').substringBefore(';').toLong() > System.currentTimeMillis(), expiry)
                    assertTrue("Max-Age" !in expiry && "Expires=" !in expiry, expiry)
                }
            }
        }
    }

    @Test
    fun `configured prefix ignores the default cookie`() {
        val res = client().send(
            get("/whoami").header("Cookie", "_bat=cookie-token").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue("token=null" in res.body(), "expected no configured cookie token: ${res.body()}")
    }

    @Test
    fun `an Authorization header wins over the cookie`() {
        val res = client().send(
            get("/whoami")
                .header("Authorization", "Bearer header-token")
                .header("Cookie", "_bat=cookie-token")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=Bearer header-token" in res.body(), "the header must win: ${res.body()}")
    }

    @Test
    fun `configured prefix selects its cookie`() {
        val selected = jwt("preview")
        val res = client().send(
            get("/whoami")
                .header("Cookie", "_bat=default-token; _bat_preview=$selected")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=$selected" in res.body(), "expected the configured cookie token: ${res.body()}")
    }

    @Test
    fun `custom cookie does not require JWT metadata`() {
        val unscoped = jwt(null)
        val res = client().send(
            get("/whoami")
                .header("Cookie", "_bat=default-token; _bat_preview=$unscoped")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=$unscoped" in res.body(), "expected the unscoped JWT fallback: ${res.body()}")
    }

    @Test
    fun `feature flag upstream failure does not fail page rendering`() {
        val res = client().send(
            get("/flag-failure").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, res.statusCode())
        assertTrue(
            "variation= value=null degraded=true" in res.body(),
            "expected degraded flag fallback: ${res.body()}",
        )
    }

    @Test
    fun `unrelated cookies do not replace the configured cookie`() {
        val res = client().send(
            get("/whoami")
                .header("Cookie", "_bat=default-token; other_cookie=${jwt("other")}")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=null" in res.body(), "expected no configured cookie token: ${res.body()}")
    }

    @Test
    fun `matching JWT in an unrelated cookie is ignored`() {
        val res = client().send(
            get("/whoami")
                .header("Cookie", "_bat=default-token; arbitrary_cookie=${jwt("preview")}")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=null" in res.body(), "expected only the configured cookie: ${res.body()}")
    }

    @Test
    fun `configured cookie is selected regardless of JWT payload`() {
        val selected = jwt("other")
        val res = client().send(
            get("/whoami")
                .header("Cookie", "_bat=default-token; _bat_preview=$selected")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=$selected" in res.body(), "expected the configured auth cookie: ${res.body()}")
    }

    @Test
    fun `missing configured access cookie does not use the default cookie`() {
        val res = client().send(
            get("/whoami")
                .header("Cookie", "_bat=default-token")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("token=null" in res.body(), "expected the preview auth cookie to remain selected: ${res.body()}")
    }

    @Test
    fun `page render without header or cookie has no token`() {
        val res = client().send(get("/whoami").build(), HttpResponse.BodyHandlers.ofString())
        assertTrue("token=null" in res.body(), "expected no token: ${res.body()}")
    }

    @Test
    fun `render context exposes request cookies and query params`() {
        val res = client().send(
            get("/whoami?q=grid&topic=science").header("Cookie", "site_theme=dark; other=1").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("theme=dark" in res.body(), "expected the site_theme cookie value: ${res.body()}")
        assertTrue("q=grid" in res.body(), "expected the q query param: ${res.body()}")
        assertTrue("topic=science" in res.body(), "expected the topic query param: ${res.body()}")
    }

    @Test
    fun `page render stores the installation identity returned by the separate analytics endpoint`() {
        val res = client().send(get("/whoami").build(), HttpResponse.BodyHandlers.ofString())
        val installationId = Regex("ffid=([A-Za-z0-9_-]+)").find(res.body())?.groupValues?.get(1)
            ?: error("render did not expose its feature flag identity: ${res.body()}")
        val cookie = res.headers().allValues("Set-Cookie")
            .single { it.startsWith("bml_iid=") }

        assertEquals("server-installation", installationId)
        assertTrue(cookie.startsWith("bml_iid=server-installation;"), cookie)
        assertTrue("Path=/" in cookie, cookie)
        assertTrue("SameSite=Lax" in cookie, cookie)
        assertTrue("Max-Age=31536000" in cookie, cookie)
        assertTrue("HttpOnly" !in cookie, "the browser runtime must be able to reconcile the identity: $cookie")
        assertTrue("Secure" !in cookie, cookie)
    }

    @Test
    fun `server flag evaluation uses the request installation identity`() {
        val res = client().send(
            get("/flag")
                .header("Cookie", "_bat_preview=flag-token; bml_iid=stable-installation")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, res.statusCode())
        assertTrue("variation=compact" in res.body(), res.body())
        assertTrue(
            res.headers().allValues("Set-Cookie").any { it.startsWith("bml_iid=stable-installation;") },
        )
    }

    @Test
    fun `feature flag cookie security follows the addressed request transport`() {
        val untrustedOrigins = listOf(
            "Origin" to "http://untrusted.example",
            "Referer" to "http://untrusted.example/page",
        )
        for ((header, value) in untrustedOrigins) {
            val res = client().send(
                get("/whoami")
                    .header("X-Forwarded-Proto", "https")
                    .header(header, value)
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val cookie = res.headers().allValues("Set-Cookie").single { it.startsWith("bml_iid=") }
            assertTrue("Secure" in cookie, "$header must not downgrade the cookie: $cookie")
            val analyticsCookie = res.headers().allValues("Set-Cookie").single { it.startsWith("bml_asid=") }
            assertTrue("Secure" in analyticsCookie, analyticsCookie)
        }
    }

    @Test
    fun `page render refreshes a valid feature flag identity without replacing it`() {
        val res = client().send(
            get("/whoami").header("Cookie", "bml_iid=stable-installation").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue("ffid=stable-installation" in res.body(), res.body())
        assertTrue(
            res.headers().allValues("Set-Cookie").any { it.startsWith("bml_iid=stable-installation;") },
        )
    }

    @Test
    fun `page render canonicalizes a quoted feature flag identity`() {
        val res = client().send(
            get("/whoami").header("Cookie", "bml_iid=\"stable-installation\"").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue("ffid=stable-installation" in res.body(), res.body())
        val cookie = res.headers().allValues("Set-Cookie").single { it.startsWith("bml_iid=") }
        assertTrue(cookie.startsWith("bml_iid=stable-installation;"), cookie)
        assertTrue("\"" !in cookie, cookie)
    }

    @Test
    fun `malformed feature flag identity is replaced before rendering`() {
        val res = client().send(
            get("/whoami").header("Cookie", "bml_iid=not.valid").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue("ffid=not.valid" !in res.body(), res.body())
        assertTrue(res.headers().allValues("Set-Cookie").any { it.startsWith("bml_iid=") })
    }

    @Test
    fun `island action resolves the token from the cookie too`() {
        val res = client().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/action"))
                .header("Content-Type", "application/json")
                .header("Cookie", "_bat_preview=action-token")
                .POST(HttpRequest.BodyPublishers.ofString("""{"page":"/whoami","stateKey":"probe","method":"show","state":""}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue("token=action-token" in res.body(), "action ctx should carry the cookie token: ${res.body()}")
        assertTrue("ffid=" in res.body(), "action ctx should carry the feature flag identity: ${res.body()}")
    }

    @Test
    fun `graphql proxy forwards the body and cookie token and passes the upstream response through`() {
        val res = client().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/graphql"))
                .header("Content-Type", "application/json")
                .header("Cookie", "_bat_preview=proxy-token; bml_iid=stable-installation")
                .header("X-BA-Session-ID", "proxy-session")
                .POST(HttpRequest.BodyPublishers.ofString("""{"query":"{ ping }"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue(""""auth":"Bearer proxy-token"""" in res.body(), "upstream must see the bearer: ${res.body()}")
        assertTrue(""""installationId":"stable-installation"""" in res.body(), "upstream must see the installation identity: ${res.body()}")
        assertTrue(""""sessionId":"proxy-session"""" in res.body(), "upstream must see the analytics session: ${res.body()}")
        assertTrue(""""echo":"{\"query\":\"{ ping }\"}"""" in res.body(), "upstream must see the body: ${res.body()}")
    }

    @Test
    fun `graphql proxy passes a non-200 upstream status through`() {
        val res = client().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("fail"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(500, res.statusCode())
        assertTrue("upstream-error" in res.body(), "the upstream error body must pass through: ${res.body()}")
        val cookie = res.headers().allValues("Set-Cookie").single { it.startsWith("bml_asid=") }
        val next = client().send(
            get("/analytics-session").header("Cookie", res.headers().allValues("Set-Cookie")
                .filter { it.startsWith("bml_asid=") || it.startsWith("bml_asid_exp=") }
                .joinToString("; ") { it.substringBefore(';') }).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("session=${cookie.substringAfter('=').substringBefore(';')}" in next.body(), next.body())
    }

    @Test
    fun `server forwards existing sessions without interpreting client expiration`() {
        for (expiry in listOf(null, "invalid", (System.currentTimeMillis() - 1).toString())) {
            val response = client().send(
                get("/analytics-session").header("Cookie", "bml_asid=expired-session" +
                    (expiry?.let { "; bml_asid_exp=$it" } ?: "")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertTrue("session=expired-session" in response.body(), response.body())
            val cookies = response.headers().allValues("Set-Cookie")
            assertTrue(cookies.none { it.startsWith("bml_asid=") || it.startsWith("bml_asid_exp=") }, cookies.toString())
        }
    }

    @Test
    fun `installation header overrides the cookie and invalid headers fall back to the cookie`() {
        for (header in listOf("browser-installation", "not.valid", "")) {
            val expected = if (header == "browser-installation") header else "cookie-installation"
            val res = client().send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/graphql"))
                    .header("Content-Type", "application/json")
                    .header("Cookie", "bml_iid=cookie-installation")
                    .header("X-Installation-ID", header)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"query":"{ ping }"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode())
            assertTrue("\"installationId\":\"$expected\"" in res.body(), res.body())
            assertTrue(res.headers().allValues("Set-Cookie").any { it.startsWith("bml_iid=$expected;") })
        }
    }

    @Test
    fun `graphql proxy relays every upstream session cookie`() {
        val res = client().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("cookies"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, res.statusCode())
        val cookies = res.headers().allValues("Set-Cookie")
        assertEquals(
            listOf(
                "_bat=; Domain=example.com; Path=/; Max-Age=0",
                "_bat=; Domain=admin.example.com; Path=/; Max-Age=0",
            ),
            cookies.filter { it.startsWith("_bat=") },
        )
        assertTrue(cookies.any { it.startsWith("bml_iid=server-installation;") })
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun client(): HttpClient = HttpClient.newHttpClient()

    private fun get(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()

    companion object {
        private var port = 0
        private var upstreamPort = 0
        private var installationUpstreamPort = 0
        private lateinit var engine: NettyServerEngine
        private lateinit var upstream: HttpServer
        private lateinit var installationUpstream: HttpServer

        private fun jwt(client: String?): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("{\"alg\":\"none\"}".toByteArray())
            val clientClaim = client?.let { ",\"client\":\"$it\"" }.orEmpty()
            val payload = encoder.encodeToString("{\"sub\":\"test\"$clientClaim}".toByteArray())
            return "$header.$payload.signature"
        }

        /** Renders everything the tests probe: token, a cookie value, and query params. */
        private val whoamiPage = object : BmlPageRenderer {
            override val route = "/whoami"
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup(
                    "<html><body>token=${ctx.token} theme=${ctx.cookies["site_theme"]} " +
                        "q=${ctx.query["q"]} topic=${ctx.query["topic"]} " +
                        "ffid=${ctx.featureFlags.installationId}</body></html>",
                )
            }
        }

        private suspend fun sessionProbe(gql: GraphQLClient): String =
            gql.execute("query AnalyticsSessionProbe { session }").toString()

        private val sessionPage = object : BmlPageRenderer {
            override val route = "/analytics-session"
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup("<html><body>${sessionProbe(ctx.gql)}</body></html>")
            }
        }

        private val sessionContract = object : BmlContractDispatcher {
            override val name = "Session"
            override val methods = listOf("read")
            override suspend fun dispatch(gql: GraphQLClient?, method: String, argsJson: String): String =
                sessionProbe(requireNotNull(gql))
        }

        private val flagPage = object : BmlPageRenderer {
            override val route = "/flag"
            override suspend fun render(ctx: RenderContext) {
                val evaluation = ctx.featureFlags.evaluate("checkout")
                ctx.writer.markup(
                    "<html><head><script>authored()</script></head><body>" +
                        "variation=${evaluation.variationKey}</body></html>",
                )
            }
        }

        private val flagFailurePage = object : BmlPageRenderer {
            override val route = "/flag-failure"
            override suspend fun render(ctx: RenderContext) {
                val evaluation = ctx.featureFlags.evaluate("unavailable")
                ctx.writer.markup(
                    "<html><body>variation=${evaluation.variationKey} value=${evaluation.value} " +
                        "degraded=${evaluation.degraded}</body></html>",
                )
            }
        }

        private val probeDispatcher = object : BmlIslandActionDispatcher {
            override val stateKey = "probe"
            override val serverScoped = false
            override suspend fun dispatch(ctx: RenderContext, method: String, state: String, args: kotlinx.serialization.json.JsonArray, instanceKey: String): IslandActionResult {
                if (method == "session") return IslandActionResult("{}", sessionProbe(ctx.gql))
                ctx.writer.markup("<p>token=${ctx.token} ffid=${ctx.featureFlags.installationId}</p>")
                return IslandActionResult("{}", ctx.writer.toString())
            }
        }

        @JvmStatic
        @BeforeClass
        fun boot() {
            // A stub "GraphQL endpoint": echoes the received Authorization + body as JSON; "fail" → 500.
            upstreamPort = ServerSocket(0).use { it.localPort }
            upstream = HttpServer.create(InetSocketAddress("localhost", upstreamPort), 0)
            upstream.createContext("/graphql") { exchange ->
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val auth = exchange.requestHeaders.getFirst("Authorization")
                val installationId = exchange.requestHeaders.getFirst("X-Installation-ID")
                val sessionId = exchange.requestHeaders.getFirst("X-BA-Session-ID")
                if (body == "cookies") {
                    exchange.responseHeaders.add("Set-Cookie", "_bat=; Domain=example.com; Path=/; Max-Age=0")
                    exchange.responseHeaders.add("Set-Cookie", "_bat=; Domain=admin.example.com; Path=/; Max-Age=0")
                }
                val (status, response) = when {
                    "AnalyticsSessionProbe" in body -> 200 to """{"data":{"session":"session=${sessionId ?: "absent"}"}}"""
                    body == "fail" -> 500 to """{"errors":[{"message":"upstream-error"}]}"""
                    "BmlEvaluateFeatureFlag" in body && "unavailable" in body ->
                        500 to """{"errors":[{"message":"feature-flags-unavailable"}]}"""
                    "BmlEvaluateFeatureFlag" in body -> 200 to """
                        {"data":{
                          "featureFlags":{"evaluate":{"flagKey":"checkout","variationKey":"compact","value":true,"experimentId":"experiment-1","degraded":false}}
                        }}
                    """.trimIndent()
                    else -> 200 to """{"auth":"$auth","installationId":"$installationId","sessionId":"$sessionId","echo":"${body.replace("\"", "\\\"")}"}"""
                }
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            upstream.start()

            // Installation registration is an analytics concern and need not share GraphQL's host.
            installationUpstreamPort = ServerSocket(0).use { it.localPort }
            installationUpstream = HttpServer.create(InetSocketAddress("localhost", installationUpstreamPort), 0)
            installationUpstream.createContext("/api/v1/installation") { exchange ->
                val bytes = """{"id":"server-installation"}""".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            installationUpstream.start()

            port = ServerSocket(0).use { it.localPort }
            val config = ApplicationConfig.load(
                "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream(),
            )
            val app = BoscaApplication(config)
            BmlServer(
                project = CompiledProject(name = "test", version = "0.0.1"),
                pages = listOf(whoamiPage, flagPage, flagFailurePage, sessionPage),
                contracts = listOf(sessionContract),
                componentRenderers = mapOf("session" to BmlComponentRenderer { ctx, _, _ ->
                    ctx.writer.markup(sessionProbe(ctx.gql))
                }),
                port = port,
                graphqlEndpoint = "http://localhost:$upstreamPort/graphql",
                installationEndpoint = "http://localhost:$installationUpstreamPort/api/v1/installation",
                islandDispatchers = mapOf("/whoami" to mapOf("probe" to probeDispatcher)),
                authCookiePrefix = "_bat_preview",
            ).install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-token-test-server") { engine.start() }
            awaitReady()
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            if (::upstream.isInitialized) upstream.stop(0)
            if (::installationUpstream.isInitialized) installationUpstream.stop(0)
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
