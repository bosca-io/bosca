package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlIslandActionDispatcher
import bosca.bml.render.BmlLocalePolicy
import bosca.bml.render.BmlLocales
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.IslandActionResult
import bosca.bml.render.InvalidBmlClientStateException
import bosca.bml.render.RenderContext
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.CookieManager
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration test for the live-island HTTP wiring in [BmlServer.install]: it boots a real (NIO) engine on
 * an ephemeral port and drives `GET <page>` + `POST /_bml/action` over HTTP — covering the parts the unit
 * tests can't reach: the `bml_session` cookie set on a server-state page render and read on the action,
 * cross-request durability via the cookie, the `410` for a missing session or state entry, the `404`
 * page-scoping, and the client-scope `{state, html}` round-trip. State models are plain `{"n":N}` JSON
 * (no serialization plugin).
 */
class BmlActionEndpointTest {

    @Test
    fun `server-state page sets an HttpOnly bml_session cookie`() {
        val res = client().send(get("/srv"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, res.statusCode())
        val setCookie = setCookie(res)
        assertTrue("bml_session=" in setCookie, "expected a bml_session cookie: $setCookie")
        assertTrue("HttpOnly" in setCookie, "session cookie must be HttpOnly: $setCookie")
        assertTrue("Path=/" in setCookie, "session cookie must be Path=/: $setCookie")
        assertTrue("Max-Age=420" in setCookie, "cookie lifetime must match the session store: $setCookie")
    }

    @Test
    fun `server-scope action persists across requests via the cookie`() {
        val c = client() // a cookie jar: the GET's bml_session is auto-sent on the POSTs
        c.send(get("/srv"), HttpResponse.BodyHandlers.ofString()) // mints + seeds the session
        val first = c.send(action("/srv", "cart", "add"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, first.statusCode())
        assertTrue("n=1" in first.body(), "first add should yield n=1: ${first.body()}")
        // server scope never returns client state
        assertTrue("\"state\":null" in first.body(), "server scope must return null state: ${first.body()}")
        assertTrue(
            first.headers().allValues("set-cookie").any { "bml_session=" in it && "Max-Age=420" in it },
            "a successful server action renews the opaque session cookie with the store lifetime",
        )
        val second = c.send(action("/srv", "cart", "add"), HttpResponse.BodyHandlers.ofString())
        assertTrue("n=2" in second.body(), "state must persist across requests (cookie session): ${second.body()}")
    }

    @Test
    fun `explicit identity cleanup replaces server-session state`() {
        val c = HttpClient.newHttpClient()
        val firstPage = c.send(get("/srv"), HttpResponse.BodyHandlers.ofString())
        val oldSession = sessionId(firstPage)
        val oldSessionCookie = "bml_session=$oldSession"
        val oldAction = c.send(
            action("/srv", "cart", "add", cookie = oldSessionCookie),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("n=1" in oldAction.body())

        val resetCookies = "$oldSessionCookie; bml_session_reset=1"
        val staleAction = c.send(
            action("/srv", "cart", "add", cookie = resetCookies),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(410, staleAction.statusCode())

        val replacement = c.send(get("/srv", resetCookies), HttpResponse.BodyHandlers.ofString())
        val newSession = sessionId(replacement)
        assertNotEquals(oldSession, newSession)
        assertTrue(
            replacement.headers().allValues("set-cookie").any {
                it.startsWith("bml_session_reset=") && "Max-Age=0" in it
            },
            replacement.headers().map().toString(),
        )
        val newAction = c.send(
            action("/srv", "cart", "add", cookie = "bml_session=$newSession"),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("n=1" in newAction.body(), "the previous identity's state must not be restored: ${newAction.body()}")
    }

    @Test
    fun `server-scope action without a session cookie is 410`() {
        val c = client()
        val sizeBefore = sessionStore.size()
        val res = c.send(action("/srv", "cart", "add"), HttpResponse.BodyHandlers.ofString())
        assertEquals(410, res.statusCode())
        assertTrue(
            res.headers().allValues("set-cookie").none { it.startsWith("bml_session=") },
            "a cookie-less action must not replace or create a session",
        )
        assertEquals(sizeBefore, sessionStore.size(), "the failed POST must not consume session capacity")

        val reload = c.send(get("/srv"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, reload.statusCode())
        assertTrue(sessionId(reload).isNotEmpty(), "the reload GET must create the fresh session")
        assertEquals(sizeBefore + 1, sessionStore.size())
    }

    @Test
    fun `server-scope action with a live session but missing state is 410`() {
        val c = client()
        val page = c.send(get("/srv"), HttpResponse.BodyHandlers.ofString()) // seeds cart, but not stale
        val oldSession = sessionId(page)
        val siteBefore = c.send(action("/srv", "site-widget.count", "add"), HttpResponse.BodyHandlers.ofString())
        assertTrue("n=1" in siteBefore.body())
        val res = c.send(action("/srv", "stale", "add"), HttpResponse.BodyHandlers.ofString())
        assertEquals(410, res.statusCode())
        assertEquals("{\"error\":\"session expired\"}", res.body())
        assertTrue(
            res.headers().allValues("set-cookie").none { it.startsWith("bml_session=") },
            "a missing state entry must not discard its live session",
        )

        // The browser reload initializes only the missing page state in the existing session.
        val reload = c.send(get("/srv"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, reload.statusCode())
        assertEquals(oldSession, sessionId(reload), "the reload must retain the live session")
        val siteAfter = c.send(action("/srv", "site-widget.count", "add"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, siteAfter.statusCode())
        assertTrue("n=2" in siteAfter.body(), "site state must survive page-state recovery: ${siteAfter.body()}")
    }

    @Test
    fun `failed server-state render keeps its selected session reachable`() {
        val c = client()
        val sizeBefore = sessionStore.size()

        val first = c.send(get("/failing-srv"), HttpResponse.BodyHandlers.ofString())
        assertEquals(500, first.statusCode())
        val sessionId = sessionId(first)
        assertTrue(sessionId.isNotEmpty(), "the error response must retain the selected session")
        assertEquals(sizeBefore + 1, sessionStore.size())

        val retry = c.send(get("/failing-srv"), HttpResponse.BodyHandlers.ofString())
        assertEquals(500, retry.statusCode())
        assertEquals(sessionId, sessionId(retry), "the retry must renew the same session")
        assertEquals(sizeBefore + 1, sessionStore.size(), "the retry must not strand another session")
    }

    @Test
    fun `action for an unknown page is 404`() {
        val res = client().send(action("/does-not-exist", "cart", "add"), HttpResponse.BodyHandlers.ofString())
        assertEquals(404, res.statusCode())
    }

    @Test
    fun `component action for an unknown page is 404`() {
        val res = client().send(
            action("/does-not-exist", "site-widget.count", "add"),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, res.statusCode())
    }

    @Test
    fun `protected page action requires authentication`() {
        val anonymous = client().send(
            action("/members", "protected", "inc", state = "{\"n\":0}"),
            HttpResponse.BodyHandlers.ofString(),
        )
        val authenticated = client().send(
            action(
                "/members",
                "protected",
                "inc",
                state = "{\"n\":0}",
                authorization = "Bearer member",
            ),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(401, anonymous.statusCode())
        assertEquals(200, authenticated.statusCode())
    }

    @Test
    fun `component action must belong to the requested page`() {
        val response = client().send(
            action("/plain", "site-client.access", "inc", state = "{\"n\":0}"),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(404, response.statusCode())
    }

    @Test
    fun `action path must match its page route`() {
        val c = client()
        c.send(get("/items/a"), HttpResponse.BodyHandlers.ofString())

        val response = c.send(
            action("/items/{id}", "cart", "add", path = "/srv"),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(422, response.statusCode())
        assertEquals("{\"error\":\"invalid action path\"}", response.body())
    }

    @Test
    fun `action for an unknown state key on a known page is 404`() {
        val res = client().send(action("/srv", "nope", "add"), HttpResponse.BodyHandlers.ofString())
        assertEquals(404, res.statusCode())
    }

    @Test
    fun `client-scope action round-trips state and html in the body`() {
        val res = client().send(
            action("/cli", "counter", "inc", state = "{\"n\":4}"),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue("\"state\":\"{\\\"n\\\":5}\"" in res.body(), "client scope returns the new state: ${res.body()}")
        assertTrue("n=5" in res.body(), "re-rendered html should reflect the new state: ${res.body()}")
    }

    @Test
    fun `action re-render receives the page query and canonical locale`() {
        val res = client().send(
            action(
                "/cli",
                "counter",
                "inc",
                state = "{\"n\":4}",
                query = """{"filter":"open"}""",
                locale = "fr",
            ),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, res.statusCode())
        assertTrue("filter=open" in res.body(), res.body())
        assertTrue("lang=fr" in res.body(), res.body())
    }

    @Test
    fun `component page scope is isolated while site scope is shared across pages`() {
        val c = client()
        c.send(get("/srv"), HttpResponse.BodyHandlers.ofString())
        val pageOne = c.send(action("/srv", "page-widget.count", "add"), HttpResponse.BodyHandlers.ofString())
        val siteOne = c.send(action("/srv", "site-widget.count", "add"), HttpResponse.BodyHandlers.ofString())
        assertTrue("n=1" in pageOne.body())
        assertTrue("n=1" in siteOne.body())

        c.send(get("/srv2"), HttpResponse.BodyHandlers.ofString())
        val pageTwo = c.send(action("/srv2", "page-widget.count", "add"), HttpResponse.BodyHandlers.ofString())
        val siteTwo = c.send(action("/srv2", "site-widget.count", "add"), HttpResponse.BodyHandlers.ofString())
        assertTrue("n=1" in pageTwo.body(), "page component state leaked across paths: ${pageTwo.body()}")
        assertTrue("n=2" in siteTwo.body(), "site component state did not cross paths: ${siteTwo.body()}")
    }

    @Test
    fun `page server state is isolated between concrete paths of one dynamic route`() {
        val c = client()
        c.send(get("/items/a"), HttpResponse.BodyHandlers.ofString())
        val itemA = c.send(
            action("/items/{id}", "cart", "add", path = "/items/a"),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("n=1" in itemA.body())

        c.send(get("/items/b"), HttpResponse.BodyHandlers.ofString())
        val itemB = c.send(
            action("/items/{id}", "cart", "add", path = "/items/b"),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue("n=1" in itemB.body(), "page state leaked between dynamic paths: ${itemB.body()}")
        assertTrue("/items/b" in itemB.body(), "action context did not carry the concrete path: ${itemB.body()}")
        assertTrue("id=b" in itemB.body(), "action context did not carry matched path params: ${itemB.body()}")
    }

    @Test
    fun `site component exact key resolves without an instance suffix`() {
        val res = client().send(
            action("/cli", "site-client.access", "inc", state = "{\"n\":4}"),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertTrue("n=5" in res.body())
    }

    @Test
    fun `incompatible client state receives the typed recovery response`() {
        val res = client().send(
            action("/cli", "invalid", "", state = "{\"n\":\"from-an-older-model\"}"),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(422, res.statusCode())
        assertEquals("{\"error\":\"invalid client state\"}", res.body())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun client(): HttpClient =
        HttpClient.newBuilder().cookieHandler(CookieManager()).build()

    private fun get(path: String, cookie: String? = null): HttpRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .apply { cookie?.let { header("Cookie", it) } }
            .GET()
            .build()

    private fun action(
        page: String,
        stateKey: String,
        method: String,
        state: String = "",
        path: String = page,
        cookie: String? = null,
        query: String = "{}",
        locale: String? = null,
        authorization: String? = null,
    ): HttpRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/action"))
            .header("Content-Type", "application/json")
            .apply { cookie?.let { header("Cookie", it) } }
            .apply { authorization?.let { header("Authorization", it) } }
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """{"page":"$page","path":"$path","query":$query,"locale":${locale?.let { "\"$it\"" } ?: "null"},"stateKey":"$stateKey","method":"$method","state":"${state.replace("\"", "\\\"")}"}""",
                ),
            )
            .build()

    private fun sessionId(response: HttpResponse<*>): String =
        setCookie(response)
            .takeIf { it.startsWith("bml_session=") }
            ?.substringAfter("bml_session=")
            ?.substringBefore(';')
            .orEmpty()

    private fun setCookie(response: HttpResponse<*>): String =
        response.headers().allValues("set-cookie")
            .firstOrNull { it.startsWith("bml_session=") }
            .orEmpty()

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine
        private val sessionStore = InMemoryBmlSessionStore(ttlMillis = 7 * 60 * 1000L)

        /** A hand-written server-scope dispatcher over plain `{"n":N}` JSON (no serialization plugin here). */
        private fun dispatcher(key: String, server: Boolean, site: Boolean = false) = object : BmlIslandActionDispatcher {
            override val stateKey = key
            override val serverScoped = server
            override val siteScoped = site
            override suspend fun dispatch(ctx: RenderContext, method: String, state: String, args: kotlinx.serialization.json.JsonArray, instanceKey: String): IslandActionResult {
                val json = if (server) ctx.session?.get(key) ?: "{\"n\":0}" else state
                var n = Regex("\"n\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (method == "add" || method == "inc") n++
                val newJson = "{\"n\":$n}"
                ctx.writer.markup(
                    "<p>n=$n path=${ctx.requestPath} id=${ctx.params["id"].orEmpty()} " +
                        "filter=${ctx.query["filter"].orEmpty()} lang=${ctx.lang}</p>",
                )
                return if (server) {
                    ctx.session?.put(key, newJson)
                    IslandActionResult(null, ctx.writer.toString())
                } else {
                    IslandActionResult(newJson, ctx.writer.toString())
                }
            }
        }

        private fun incompatibleClientStateDispatcher() = object : BmlIslandActionDispatcher {
            override val stateKey = "invalid"
            override val serverScoped = false
            override suspend fun dispatch(ctx: RenderContext, method: String, state: String, args: kotlinx.serialization.json.JsonArray, instanceKey: String): IslandActionResult {
                throw InvalidBmlClientStateException(SerializationException("field 'n' has the old type"))
            }
        }

        private fun page(
            r: String,
            server: Boolean,
            tags: List<String> = emptyList(),
            authRequired: Boolean = false,
        ) = object : BmlPageRenderer {
            override val route = r
            override val hasServerState = server
            override val componentTags = tags
            override val requiresAuth = authRequired
            override suspend fun render(ctx: RenderContext) {
                if (server) {
                    val pageCart = bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, "cart")
                    ctx.session?.put(pageCart, ctx.session?.get(pageCart) ?: "{\"n\":0}")
                    val pageComponent = bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, "page-widget.count")
                    ctx.session?.put(pageComponent, ctx.session?.get(pageComponent) ?: "{\"n\":0}")
                    ctx.session?.put("site-widget.count", ctx.session?.get("site-widget.count") ?: "{\"n\":0}")
                }
                ctx.writer.markup("<html><body>$r</body></html>")
            }
        }

        private fun failingServerPage() = object : BmlPageRenderer {
            override val route = "/failing-srv"
            override val hasServerState = true
            override suspend fun render(ctx: RenderContext) {
                ctx.session?.put("partial", "{\"initialized\":true}")
                error("render failed after initializing server state")
            }
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
                pages = listOf(
                    page("/srv", server = true, tags = listOf("page-widget", "site-widget")),
                    page("/srv2", server = true, tags = listOf("page-widget", "site-widget")),
                    page("/items/{id}", server = true),
                    page("/cli", server = false, tags = listOf("site-client")),
                    page("/plain", server = false),
                    page("/members", server = false, authRequired = true),
                    failingServerPage(),
                ),
                port = port,
                sessions = sessionStore,
                localePolicy = BmlLocalePolicy.static(BmlLocales(listOf("en", "fr"))),
                islandDispatchers = mapOf(
                    "/srv" to mapOf(
                        "cart" to dispatcher("cart", server = true),
                        "stale" to dispatcher("stale", server = true),
                    ),
                    "/items/{id}" to mapOf("cart" to dispatcher("cart", server = true)),
                    "/cli" to mapOf(
                        "counter" to dispatcher("counter", server = false),
                        "invalid" to incompatibleClientStateDispatcher(),
                    ),
                    "/members" to mapOf("protected" to dispatcher("protected", server = false)),
                ),
                componentIslandDispatchers = mapOf(
                    "page-widget.count" to dispatcher("page-widget.count", server = true),
                    "site-widget.count" to dispatcher("site-widget.count", server = true, site = true),
                    "site-client.access" to dispatcher("site-client.access", server = false, site = true),
                ),
            ).install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-action-test-server") { engine.start() }
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
