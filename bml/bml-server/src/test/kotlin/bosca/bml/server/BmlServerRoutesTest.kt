package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.project.DiscoveryFiles
import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration coverage for [BmlServer.install]'s full route/render pipeline over real HTTP: linked-asset CSS
 * injection, global JS + client-module + dev-reload injection, the served global/per-component stylesheets,
 * the sliver `/_bml/render/{tag}` endpoint (known + unknown), and the discovery files.
 */
class BmlServerRoutesTest {

    @Test fun `page render injects linked css, global js, client module, and dev reload`() {
        val html = get("/")
        assertTrue("/_bml/app.css" in html, html)        // global stylesheet (linked mode)
        assertTrue("/_bml/css/badge.css" in html, html)  // per-component chunk for what the page renders
        assertTrue("/_bml/app.js" in html, html)         // global js
        assertTrue("/_bml/js/Page.js" in html, html)     // the page's client bundle
        assertTrue("/_bml/js/Badge.js" in html, html)    // a rendered component's own client module
        assertTrue("/_bml/reload" in html, html)         // dev live-reload client
    }

    @Test fun `ordinary parameterized page includes canonical action context`() {
        val html = get("/accounts/42")

        assertTrue("data-bml-page=\"/accounts/{id}\" data-bml-page-canonical" in html, html)
        assertTrue("data-bml-page-path=\"/accounts/42\"" in html, html)
        assertEquals(1, Regex("data-bml-page-canonical").findAll(html).count(), html)
    }

    @Test fun `personalized page responses forbid shared caching exactly once`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/accounts/42")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(listOf("private, no-cache"), response.headers().allValues("Cache-Control"))
    }

    @Test fun `non-HTML pages keep their existing caching`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/feed.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals(emptyList(), response.headers().allValues("Cache-Control"))
    }

    @Test fun `global stylesheet is served`() = assertEquals("body{margin:0}", get("/_bml/app.css"))

    @Test fun `page renders see the ambient currentRenderContext`() {
        assertTrue("AMBIENT OK" in get("/ambient"), get("/ambient"))
        assertTrue("PATH /ambient" in get("/ambient"), get("/ambient"))
    }

    @Test fun `global js is served`() = assertEquals("console.log('app')", get("/_bml/app.js"))

    @Test fun `auth cookie prefix is prepended to BML browser configuration`() {
        assertEquals(
            "globalThis.bmlConfig=Object.freeze({\"authCookiePrefix\":\"_bat_preview\"});\nconsole.log('app')",
            bmlGlobalJs("console.log('app')", " _bat_preview "),
        )
        assertEquals("console.log('app')", bmlGlobalJs("console.log('app')", null))
    }

    @Test fun `per-component stylesheet is served`() {
        assertTrue("badge" in get("/_bml/css/badge.css"))
    }

    @Test fun `sliver re-render returns the component fragment`() {
        val (code, body) = post("/_bml/render/badge", "{}")
        assertEquals(200, code)
        assertTrue("BADGE" in body, body)
    }

    @Test fun `sliver re-render of an unknown component returns a comment`() {
        val (code, body) = post("/_bml/render/nope", "{}")
        assertEquals(200, code)
        assertTrue("unknown bml component: nope" in body, body)
    }

    @Test fun `discovery files are served`() {
        val sitemap = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/sitemap.xml")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, sitemap.statusCode())
        assertEquals("application/xml", sitemap.headers().firstValue("Content-Type").orElse(null))
        assertTrue("<urlset" in sitemap.body())
        assertTrue("User-agent: *" in get("/robots.txt"))
        assertTrue("# Test Site" in get("/llms.txt"))
    }

    @Test fun `an exact BML sitemap page overrides the compiled discovery fallback as XML`() {
        val customXml = """<?xml version="1.0"?><urlset><url><loc>https://example.test/custom</loc></url></urlset>"""
        val customSitemap = object : BmlPageRenderer {
            override val route = "/sitemap.xml"
            override val contentType = "application/xml"
            override val clientModule = "Sitemap.js"
            override val componentTags = listOf("badge")
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup(customXml)
            }
        }
        val replacement = BmlServer(
            project = CompiledProject(
                name = "t",
                version = "0.0.2",
                discovery = DiscoveryFiles(sitemap = "<urlset><url><loc>generated fallback</loc></url></urlset>"),
            ),
            pages = listOf(customSitemap),
            port = port,
            dev = true,
            publicDir = publicDir,
            publicCacheControl = "public, max-age=31536000, immutable",
            components = listOf(BmlComponentInfo("badge", "badge", ".badge{color:red}", emptyList())),
            globalCss = "body{margin:1px}",
            globalJs = "console.log('must not be injected')",
        )
        val rollback = server.hotSwapFrom(replacement)
        try {
            val response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/sitemap.xml")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertEquals("application/xml", response.headers().firstValue("Content-Type").orElse(null))
            assertEquals(customXml, response.body())
            assertTrue("/_bml/" !in response.body(), response.body())
        } finally {
            rollback()
        }
    }

    @Test fun `a page can return a non-HTML content type without HTML asset injection`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/feed.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null))
        assertEquals("""{"items":[]}""", response.body())
    }

    @Test fun `a page that sets redirectTo responds 302 with the location instead of its body`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/private")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(302, response.statusCode())
        assertEquals("/account", response.headers().firstValue("Location").orElse(null))
        assertTrue("SECRET" !in response.body(), response.body())
    }

    @Test fun `a page can mark its redirect permanent`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/moved")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(301, response.statusCode())
        assertEquals("/new-home", response.headers().firstValue("Location").orElse(null))
    }

    @Test fun `a page that sets notFound responds 404 with the not-found page instead of its body`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/missing?source=search")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, response.statusCode())
        assertTrue("NOT FOUND PAGE" in response.body(), response.body())
        assertTrue("source=search" in response.body(), response.body())
        assertTrue("GHOST" !in response.body(), response.body())
        // The not-found page goes through the same finishing pass as any page (linked-css tier).
        assertTrue("/_bml/app.css" in response.body(), response.body())
    }

    @Test fun `a page render that throws responds 500 with the error page instead of its body`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/exploding?source=search")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(500, response.statusCode())
        assertTrue("ERROR PAGE" in response.body(), response.body())
        assertTrue("session=true" in response.body(), response.body())
        assertTrue("source=search" in response.body(), response.body())
        assertTrue("data-bml-page-path=\"/500\"" in response.body(), response.body())
        assertTrue(
            response.headers().allValues("Set-Cookie").any { it.startsWith("bml_session=") },
            response.headers().map().toString(),
        )
        assertTrue("PARTIAL" !in response.body(), response.body())
    }

    @Test fun `the error page is also directly routable, answering 200`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/500")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertTrue("ERROR PAGE" in response.body(), response.body())
    }

    @Test fun `the not-found page is also directly routable, answering 200`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/404")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertTrue("NOT FOUND PAGE" in response.body(), response.body())
    }

    @Test fun `a requireAuth page redirects token-less callers to sign in with the return path`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/members?tab=recent")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(302, response.statusCode())
        assertEquals("/signin?redirect=%2Fmembers%3Ftab%3Drecent", response.headers().firstValue("Location").orElse(null))
        assertTrue("MEMBERS" !in response.body(), response.body())
    }

    @Test fun `dev mode serves every asset tier with no-cache plus an ETag, overriding the public cache policy`() {
        // This suite boots with dev = true: the browser must revalidate every use (so edits land
        // on the next reload) but an unchanged asset must NOT re-download — no-cache + a validator,
        // never no-store, which re-fetched every bundle/stylesheet/font on every navigation and
        // made fast clicking pile page loads up behind the browser's per-origin connection budget.
        // The header appearing on /asset.css also proves the Router's staticFiles cacheControl
        // threading (the same mechanism prod uses for the long-lived policy).
        for (path in listOf("/asset.css", "/_bml/app.css", "/_bml/app.js", "/_bml/css/badge.css")) {
            val response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode(), path)
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(null), path)
            assertTrue(response.headers().firstValue("ETag").isPresent, "$path is missing an ETag")
        }
    }

    @Test fun `every asset tier answers a matching If-None-Match with a body-less 304`() {
        for (path in listOf("/asset.css", "/_bml/app.css", "/_bml/app.js", "/_bml/css/badge.css")) {
            val first = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val etag = first.headers().firstValue("ETag").orElse(null)
            assertTrue(!etag.isNullOrEmpty(), "$path served no ETag")
            val revalidated = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                    .header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(304, revalidated.statusCode(), path)
            assertEquals("", revalidated.body(), path)
            // a changed asset (mismatched validator) still comes back in full
            val fresh = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                    .header("If-None-Match", "\"something-else\"").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, fresh.statusCode(), path)
            assertTrue(fresh.body().isNotEmpty(), path)
        }
    }

    @Test fun `the dev reload stream announces the boot id over a real SSE connection`() {
        // EventSource sends Accept: text/event-stream — the SSE route resolves only for it.
        val connection = java.net.URI.create("http://localhost:$port${BmlDevReload.ENDPOINT}").toURL()
            .openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.readTimeout = 5000
        try {
            assertEquals(200, connection.responseCode)
            assertEquals("text/event-stream", connection.contentType)
            val first = connection.inputStream.bufferedReader().readLine()
            assertTrue(first.orEmpty().startsWith("data: "), "expected the boot id data frame, got: [$first]")
            assertTrue(first.orEmpty().removePrefix("data: ").isNotBlank(), "boot id must be non-empty")
        } finally {
            connection.disconnect()
        }
    }

    @Test fun `hot swap replaces pages and assets and announces a generation without restarting the server`() {
        val connection = java.net.URI.create("http://localhost:$port${BmlDevReload.ENDPOINT}").toURL()
            .openConnection() as java.net.HttpURLConnection
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.readTimeout = 5000
        val reader = connection.inputStream.bufferedReader()
        val before = readSseData(reader)
        val cssBefore = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/app.css")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val oldCssEtag = cssBefore.headers().firstValue("ETag").orElseThrow()
        val replacement = BmlServer(
            project = CompiledProject(name = "t", version = "0.0.2"),
            pages = listOf(
                object : BmlPageRenderer {
                    override val route = "/"
                    override suspend fun render(ctx: RenderContext) {
                        ctx.writer.markup("<html><head></head><body>HOT SWAPPED</body></html>")
                    }
                },
                object : BmlPageRenderer {
                    override val route = "/added/{id}"
                    override suspend fun render(ctx: RenderContext) {
                        ctx.writer.markup("<html><body>ADDED ${ctx.params["id"]}</body></html>")
                    }
                },
                object : BmlPageRenderer {
                    override val route = "/hot-auth"
                    override val requiresAuth = true
                    override suspend fun render(ctx: RenderContext) {
                        ctx.writer.markup("<html><body>HOT AUTH</body></html>")
                    }
                },
            ),
            port = port,
            dev = true,
            publicDir = publicDir,
            publicCacheControl = "public, max-age=31536000, immutable",
            globalCss = "body{margin:1px}",
            globalJs = "console.log('hot')",
            authCookiePrefix = "_bat_hot",
        )
        val rollback = server.hotSwapFrom(replacement)
        try {
            val after = readSseData(reader)
            assertNotEquals(before, after)
            assertEquals(before.substringBeforeLast(':'), after.substringBeforeLast(':'))
            assertTrue("HOT SWAPPED" in get("/"))
            assertTrue("ADDED 42" in get("/added/42"))
            assertTrue("\"authCookiePrefix\":\"_bat_hot\"" in get("/_bml/app.js"))
            val authenticated = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/hot-auth"))
                    .header("Cookie", "_bat_hot=tok").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, authenticated.statusCode())
            assertTrue("HOT AUTH" in authenticated.body(), authenticated.body())
            val removed = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/ambient")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(404, removed.statusCode())
            val changedCss = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/app.css"))
                    .header("If-None-Match", oldCssEtag).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, changedCss.statusCode(), "the old generation's ETag must not produce a 304")
            assertEquals("body{margin:1px}", changedCss.body())
            assertNotEquals(oldCssEtag, changedCss.headers().firstValue("ETag").orElseThrow())
        } finally {
            rollback()
            connection.disconnect()
        }
    }

    @Test fun `hot swapped assets with colliding string hashes still receive distinct validators`() {
        val firstCss = "a{--x:Aa}"
        val secondCss = "a{--x:BB}"
        assertEquals(firstCss.hashCode(), secondCss.hashCode())

        fun replacement(css: String) = BmlServer(
            project = CompiledProject(name = "t", version = "0.0.2"),
            pages = emptyList(),
            port = port,
            dev = true,
            publicDir = publicDir,
            publicCacheControl = "public, max-age=31536000, immutable",
            globalCss = css,
        )

        val restoreOriginal = server.hotSwapFrom(replacement(firstCss))
        try {
            val first = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/app.css")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, first.statusCode())
            assertEquals(firstCss, first.body())
            val firstEtag = first.headers().firstValue("ETag").orElseThrow()

            val restoreFirst = server.hotSwapFrom(replacement(secondCss))
            try {
                val revalidated = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/app.css"))
                        .header("If-None-Match", firstEtag).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, revalidated.statusCode())
                assertEquals(secondCss, revalidated.body())
                assertNotEquals(firstEtag, revalidated.headers().firstValue("ETag").orElseThrow())
            } finally {
                restoreFirst()
            }
        } finally {
            restoreOriginal()
        }
    }

    @Test fun `a requireAuth page renders for a caller with the bml token cookie`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/members"))
                .header("Cookie", "_bat=tok").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertTrue("MEMBERS" in response.body(), response.body())
    }

    // ── boot / helpers ────────────────────────────────────────────────────────

    private fun get(path: String): String =
        client.send(HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

    private fun post(path: String, body: String): Pair<Int, String> {
        val r = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return r.statusCode() to r.body()
    }

    private fun readSseData(reader: java.io.BufferedReader): String {
        repeat(20) {
            val line = reader.readLine() ?: error("reload stream closed before a data event")
            if (line.startsWith("data: ")) return line.removePrefix("data: ")
        }
        error("reload stream did not produce a data event")
    }

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine
        private lateinit var server: BmlServer
        private lateinit var publicDir: java.io.File
        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            val page = object : BmlPageRenderer {
                override val route = "/"
                override val clientModule = "Page.js"
                override val componentTags = listOf("badge")
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("""<html><head></head><body><span data-bml-c="badge" class="badge">hi</span></body></html>""")
                }
            }
            val account = object : BmlPageRenderer {
                override val route = "/accounts/{id}"
                override val componentTags = listOf("badge")
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>ACCOUNT ${ctx.params["id"]}</body></html>")
                }
            }
            val guarded = object : BmlPageRenderer {
                override val route = "/private"
                override suspend fun render(ctx: RenderContext) {
                    ctx.redirectTo = "/account"
                    ctx.writer.markup("<html><body>SECRET</body></html>")
                }
            }
            val membersOnly = object : BmlPageRenderer {
                override val route = "/members"
                override val requiresAuth = true
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>MEMBERS</body></html>")
                }
            }
            val moved = object : BmlPageRenderer {
                override val route = "/moved"
                override suspend fun render(ctx: RenderContext) {
                    ctx.redirectTo = "/new-home"
                    ctx.redirectPermanent = true
                }
            }
            val missing = object : BmlPageRenderer {
                override val route = "/missing"
                override suspend fun render(ctx: RenderContext) {
                    ctx.notFound = true
                    ctx.writer.markup("<html><body>GHOST</body></html>")
                }
            }
            val notFound = object : BmlPageRenderer {
                override val route = "/404"
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup(
                        "<html><head></head><body>NOT FOUND PAGE source=${ctx.query["source"]}</body></html>",
                    )
                }
            }
            val exploding = object : BmlPageRenderer {
                override val route = "/exploding"
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>PARTIAL")
                    error("upstream failure")
                }
            }
            val errorPage = object : BmlPageRenderer {
                override val route = "/500"
                override val hasServerState = true
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup(
                        "<html><head></head><body>ERROR PAGE session=${ctx.session != null} " +
                            "source=${ctx.query["source"]}</body></html>",
                    )
                }
            }
            val ambient = object : BmlPageRenderer {
                override val route = "/ambient"
                override suspend fun render(ctx: RenderContext) {
                    // The server installs ctx on the coroutine context — site code reaches it
                    // ambiently instead of threading the parameter.
                    val same = bosca.bml.render.currentRenderContext() === ctx
                    ctx.writer.markup(
                        "<html><body>AMBIENT ${if (same) "OK" else "MISMATCH"} PATH ${ctx.requestPath}</body></html>",
                    )
                }
            }
            val feed = object : BmlPageRenderer {
                override val route = "/feed.json"
                override val contentType = "application/json"
                override val clientModule = "Feed.js"
                override val componentTags = listOf("badge")
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("""{"items":[]}""")
                }
            }
            publicDir = java.nio.file.Files.createTempDirectory("bml-public-test").toFile()
                .also { it.resolve("asset.css").writeText("cached-asset") }
            server = BmlServer(
                project = CompiledProject(
                    name = "t", version = "0.0.1",
                    discovery = DiscoveryFiles(
                        sitemap = """<?xml version="1.0"?><urlset></urlset>""",
                        robots = "User-agent: *\nDisallow:",
                        llms = "# Test Site",
                    ),
                ),
                pages = listOf(
                    page,
                    account,
                    guarded,
                    membersOnly,
                    moved,
                    missing,
                    notFound,
                    exploding,
                    errorPage,
                    ambient,
                    feed,
                ),
                signInRoute = "/signin",
                notFoundRoute = "/404",
                errorRoute = "/500",
                publicDir = publicDir,
                publicCacheControl = "public, max-age=31536000, immutable",
                port = port,
                dev = true,
                components = listOf(
                    BmlComponentInfo("badge", "badge", """.badge[data-bml-c="badge"]{color:red}""", emptyList(), clientModule = "Badge.js"),
                ),
                globalCss = "body{margin:0}",
                globalJs = "console.log('app')",
                componentRenderers = mapOf<String, BmlComponentRenderer>(
                    "badge" to BmlComponentRenderer { ctx, _, _ -> ctx.writer.markup("<span>BADGE</span>") },
                ),
            )
            val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream()))
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-routes-test-server") { engine.start() }
            repeat(100) {
                try { java.net.Socket("localhost", port).close(); return } catch (_: Exception) { Thread.sleep(50) }
            }
            error("server did not start on $port")
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
        }
    }
}
