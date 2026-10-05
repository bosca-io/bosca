@file:OptIn(org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class)

package bosca.bml.message.server

import bosca.bml.compiler.plugin.BmlAdditionalSourcesExtension
import bosca.bml.compiler.plugin.BmlConfigKeys
import bosca.bml.message.client.EmailLinkClicked
import bosca.bml.message.client.EmailOpened
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole server against a fake raw-artifacts registry: warmup → private render API (with
 * version-pinned assetsUrl derivation and engagement link rewriting) → public version-pinned
 * asset serving + tracked click/open routes → poll-driven hot reload where OLD versions'
 * assets stay servable after a new publish.
 */
class MessageServerRoutesTest {

    @Test
    fun `renders the active version with a version-pinned derived assetsUrl`() {
        val (code, body) = post(
            "/render/acme/welcome",
            """{"recipientName":"Ada"}""",
        )
        assertEquals(200, code, body)
        assertTrue(""""subject":"Hello Ada"""" in body, body)
        assertTrue("content-one" in body, body)
        assertTrue("http://public.example/assets/acme/1.0/logo.png" in body, body)
        assertTrue(""""version":"1.0"""" in body, body)
    }

    @Test
    fun `render requires an authorization bearer`() {
        val (code, body) = post("/render/acme/welcome", "{}", bearerToken = null)
        assertEquals(401, code, body)
        assertTrue("Authorization bearer token is required" in body, body)
    }

    @Test
    fun `render passes the authorization bearer through to template graphql`() {
        val (code, body) = post("/render/graphqly/viewer", "{}", bearerToken = "fresh-service-jwt")
        assertEquals(200, code, body)
        assertTrue("service-account" in body, body)
        assertEquals("Bearer fresh-service-jwt", lastGraphqlAuthorization.get())
    }

    @Test
    fun `an unknown template answers 404 with a typed error`() {
        val (code, body) = post("/render/acme/nope", "{}")
        assertEquals(404, code, body)
        assertTrue("nope" in body, body)
    }

    @Test
    fun `an unhosted project answers 503`() {
        val (code, body) = post("/render/ghost/welcome", "{}")
        assertEquals(503, code, body)
        assertTrue("no active version" in body, body)
    }

    @Test
    fun `a malformed render request answers 400`() {
        val (code, body) = post("/render/acme/welcome", "{not json")
        assertEquals(400, code, body)
    }

    @Test
    fun `assets serve from the version's jar with immutable caching`() {
        val response = get("/assets/acme/1.0/logo.png")
        assertEquals(200, response.statusCode())
        assertEquals("png-bytes-one", response.body())
        assertEquals("public, max-age=31536000, immutable", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(null))
    }

    @Test
    fun `a missing asset answers 404`() {
        assertEquals(404, get("/assets/acme/1.0/missing.png").statusCode())
        assertEquals(404, get("/assets/acme/9.9/logo.png").statusCode())
        assertEquals(404, get("/assets/ghost/1.0/logo.png").statusCode())
    }

    @Test
    fun `the typed client renders and maps failures to typed exceptions`() {
        val typed = bosca.bml.message.client.BmlMessageServerClient("http://localhost:$port")
        val rendered = runBlocking {
            typed.render(
                "acme",
                "welcome",
                bosca.bml.message.client.RenderRequest(recipientName = "Grace"),
                "service-jwt",
            )
        }
        val email = requireNotNull(rendered.email)
        assertEquals("Hello Grace", email.subject)
        assertEquals("1.0", rendered.version)
        assertTrue("content-one" in email.html, email.html)

        val unknown = runCatching {
            runBlocking { typed.render("acme", "nope", bosca.bml.message.client.RenderRequest(), "service-jwt") }
        }.exceptionOrNull() as? bosca.bml.message.client.BmlMessageRenderException
        assertEquals(404, unknown?.status)
        assertTrue("nope" in unknown?.message.orEmpty(), unknown?.message.orEmpty())

        val unhosted = runCatching {
            runBlocking { typed.render("ghost", "welcome", bosca.bml.message.client.RenderRequest(), "service-jwt") }
        }.exceptionOrNull() as? bosca.bml.message.client.BmlMessageRenderException
        assertEquals(503, unhosted?.status)
    }

    @Test
    fun `a channel-specific render does not evaluate the companion channel`() {
        val typed = bosca.bml.message.client.BmlMessageServerClient("http://localhost:$port")
        val email = runBlocking {
            typed.render(
                "dual-channel",
                "invitation",
                bosca.bml.message.client.RenderRequest(
                    channel = bosca.bml.message.client.RenderChannel.EMAIL,
                ),
                "service-jwt",
            )
        }
        assertEquals("Invitation", email.email?.subject)
        assertEquals(null, email.push)

        val push = runBlocking {
            typed.render(
                "dual-channel",
                "invitation",
                bosca.bml.message.client.RenderRequest(
                    channel = bosca.bml.message.client.RenderChannel.PUSH,
                    pushOptions = bosca.bml.message.client.RenderPushOptions(
                        defaultAction = bosca.bml.message.client.RenderPushAction(
                            id = "open",
                            url = "https://example.com/invitations/1",
                        ),
                    ),
                ),
                "service-jwt",
            )
        }
        assertEquals(null, push.email)
        assertEquals("Open invitation", push.push?.options?.defaultAction?.label)
    }

    @Test
    fun `liveness answers without touching any dependency`() {
        val response = get("/api/v1/live")
        assertEquals(200, response.statusCode())
        assertEquals("""{"status":"live"}""", response.body())
    }

    @Test
    fun `readiness answers ready once warmup completed`() {
        val response = get("/api/v1/ready")
        assertEquals(200, response.statusCode())
        assertEquals("""{"status":"ready"}""", response.body())
    }

    @Test
    fun `health lists hosted projects, their versions, and templates`() {
        val response = get("/api/v1/health")
        assertEquals(200, response.statusCode())
        assertTrue(""""project":"acme"""" in response.body(), response.body())
        assertTrue(""""welcome"""" in response.body(), response.body())
        assertTrue(""""ok":true""" in response.body(), response.body())
    }

    @Test
    fun `a new publish hot-reloads via the poll while old-version assets keep serving`() {
        // Isolated on its own project so the publish can't disturb acme's fixed-version tests.
        registry.publish("reloady", "2.0", buildMessageJar("two"))
        val deadline = System.currentTimeMillis() + 15_000
        var latest = ""
        while (System.currentTimeMillis() < deadline) {
            latest = post("/render/reloady/welcome", """{"recipientName":"Ada"}""").second
            if ("content-two" in latest) break
            Thread.sleep(250)
        }
        assertTrue("content-two" in latest, "poll never activated 2.0: $latest")
        assertTrue("http://public.example/assets/reloady/2.0/logo.png" in latest, latest)

        // The new version's assets serve…
        assertEquals("png-bytes-two", get("/assets/reloady/2.0/logo.png").body())
        // …and the OLD version's assets STILL serve — delivered emails reference them forever.
        assertEquals("png-bytes-reload-one", get("/assets/reloady/1.0/logo.png").body())
    }

    @Test
    fun `a template that runs past the timeout is cancelled with a typed failure`() {
        val started = System.currentTimeMillis()
        val (code, body) = post("/render/guardrails/slow", "{}")
        val elapsed = System.currentTimeMillis() - started
        assertEquals(500, code, body)
        assertTrue("timed out" in body, body)
        assertTrue(elapsed < 15_000, "timeout must bound the render, took ${elapsed}ms")
        // The server keeps serving normally afterwards — the runaway render didn't wedge it.
        assertEquals(200, post("/render/acme/welcome", "{}").first)
    }

    @Test
    fun `bml-inline styles land on elements, at-rules are retained, and used images attach`() {
        val typed = bosca.bml.message.client.BmlMessageServerClient("http://localhost:$port")
        val rendered = runBlocking {
            typed.render(
                "inliny",
                "fancy",
                bosca.bml.message.client.RenderRequest(recipientName = "Ada"),
                "service-jwt",
            )
        }
        val email = requireNotNull(rendered.email)
        // The simple rule inlined onto the matching element; no <style> re-emits it.
        assertTrue("""<p class="cta" style="color: #ffffff">""" in email.html, email.html)
        // The @media rule can't be inlined — it is retained as a <style> block.
        assertTrue("@media (max-width: 600px)" in email.html, email.html)
        assertTrue(""".cta { color""" !in email.html.substringAfter("</style>"), email.html)
        // The image references its Content-ID; the response carries a REFERENCE (validated
        // against the bundle), and the bytes come from the version-pinned asset route so the
        // send side caches them once per immutable (project, version, source).
        assertTrue("""src="cid:logo-png"""" in email.html, email.html)
        val image = email.images.single()
        assertEquals("logo-png", image.cid)
        assertEquals("logo.png", image.source)
        assertEquals("image/png", image.mediaType)
        assertEquals("logo.png", image.filename)
        val bytes = runBlocking { typed.asset(rendered.project, rendered.version, image.source) }
        assertEquals("png-bytes-inliny", bytes.decodeToString())

        // An <if> that skips the image attaches nothing — collection follows real control flow.
        val impersonal = runBlocking {
            typed.render("inliny", "fancy", bosca.bml.message.client.RenderRequest(), "service-jwt")
        }
        val impersonalEmail = requireNotNull(impersonal.email)
        assertTrue(impersonalEmail.images.isEmpty(), impersonalEmail.images.toString())
        assertTrue("cid:" !in impersonalEmail.html, impersonalEmail.html)
    }

    @Test
    fun `an explicit version renders that published version with version-pinned assets`() {
        // Default renders the active (latest) version…
        val active = post("/render/pinny/welcome", "{}").second
        assertTrue("content-pin-two" in active, active)
        // …an explicit older version renders THAT version, assets pinned to it (rollback path).
        val (code, pinned) = post("/render/pinny/welcome", """{"version":"1.0"}""")
        assertEquals(200, code, pinned)
        assertTrue("content-pin-one" in pinned, pinned)
        assertTrue("http://public.example/assets/pinny/1.0/logo.png" in pinned, pinned)
        assertTrue(""""version":"1.0"""" in pinned, pinned)
        // A version the registry doesn't have fails typed, not with a silent fallback.
        val (missing, body) = post("/render/pinny/welcome", """{"version":"9.9"}""")
        assertEquals(404, missing, body)
        assertTrue("not available" in body, body)
    }

    @Test
    fun `the project catalog lists hosted projects with templates and published versions`() {
        val response = get("/projects")
        assertEquals(200, response.statusCode())
        assertTrue(""""project":"acme","activeVersion":"1.0","templates":[{"key":"welcome"}]""" in response.body(), response.body())
        assertTrue(""""project":"pinny"""" in response.body(), response.body())

        val versions = get("/projects/pinny/versions")
        assertEquals(200, versions.statusCode())
        assertEquals("""["2.0","1.0"]""", versions.body())

        assertEquals(404, get("/projects/ghost/versions").statusCode())
    }

    @Test
    fun `a template that declares its payload ships a sample skeleton and schema in the catalog`() {
        val body = get("/projects").body()
        assertTrue(
            """{"key":"enroll","samplePayload":{"courseName":"","classNumber":0,"teacher":null},""" +
                """"payloadSchema":{"type":"object","properties":{"courseName":{"type":"string"},""" +
                """"classNumber":{"type":"integer"},"teacher":{"type":"string"}},""" +
                """"required":["courseName","classNumber","teacher"]}}""" in body,
            body,
        )
    }

    @Test
    fun `a message id rewrites content links, spares unsubscribe, and injects the open pixel`() {
        val rendered = requireNotNull(renderTracked(messageId = "m-1").email)
        assertTrue("http://public.example/c/" in rendered.html, rendered.html)
        assertTrue("href=\"https://example.com/course" !in rendered.html, rendered.html)
        // Deliverability: unsubscribing must never depend on the tracker.
        assertTrue("href=\"https://example.com/unsub\"" in rendered.html, rendered.html)
        assertTrue("http://public.example/o/" in rendered.html, rendered.html)
        // The plain-text alternative keeps its original links.
        assertTrue("/c/" !in rendered.text, rendered.text)
    }

    @Test
    fun `no message id means no rewriting even with tracking configured`() {
        val rendered = requireNotNull(renderTracked(messageId = null).email)
        assertTrue("/c/" !in rendered.html, rendered.html)
        assertTrue("/o/" !in rendered.html, rendered.html)
        assertTrue("href=\"https://example.com/course?id=7\"" in rendered.html, rendered.html)
    }

    @Test
    fun `a tracked click redirects to the original url and emits engagement events`() {
        val html = requireNotNull(renderTracked(messageId = "m-2").email).html
        val token = Regex("""/c/([A-Za-z0-9_\-.]+)"""").find(html)?.groupValues?.get(1)
            ?: error("no click token in: $html")

        val response = get("/c/$token")
        assertEquals(302, response.statusCode())
        assertEquals("https://example.com/course?id=7", response.headers().firstValue("Location").orElse(null))
        assertEquals(302, get("/c/$token").statusCode())

        val event = analytics.events.last { it.element?.type == "email-click" }
        assertEquals(bosca.analytics.model.EventType.Interaction, event.type)
        assertTrue(""""messageId":"m-2"""" in event.element?.extras.toString(), event.element?.extras.toString())
        assertTrue(""""url":"https://example.com/course?id=7"""" in event.element?.extras.toString())
        val clicked = pubSub.published
            .filter { it.first == EmailLinkClicked.CHANNEL }
            .takeLast(2)
            .map { Json.decodeFromString(EmailLinkClicked.serializer(), it.second) }
        assertEquals(2, clicked.size)
        assertNotEquals(clicked[0].id, clicked[1].id)
        assertEquals("m-2", clicked.last().messageId)
        assertTrue(clicked.all { it.id.startsWith("bml-") }, clicked.toString())
    }

    @Test
    fun `a tampered token answers 404 and emits nothing`() {
        val html = requireNotNull(renderTracked(messageId = "m-3").email).html
        val token = Regex("""/c/([A-Za-z0-9_\-.]+)"""").find(html)!!.groupValues[1]
        val eventsBefore = analytics.events.size
        val publishedBefore = pubSub.published.size

        assertEquals(404, get("/c/${token.dropLast(2)}xx").statusCode())
        assertEquals(404, get("/o/garbage").statusCode())

        assertEquals(eventsBefore, analytics.events.size)
        assertEquals(publishedBefore, pubSub.published.size)
    }

    @Test
    fun `the open pixel answers an uncacheable gif and emits open events`() {
        val pixelPath = tracking.openPixelUrl("m-4", "r-4").removePrefix("http://public.example")
        val response = get(pixelPath)
        assertEquals(200, response.statusCode())
        assertEquals("image/gif", response.headers().firstValue("Content-Type").orElse(null))
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals(200, get(pixelPath).statusCode())

        val event = analytics.events.last { it.element?.type == "email-open" }
        assertEquals(bosca.analytics.model.EventType.Impression, event.type)
        assertTrue(""""recipientId":"r-4"""" in event.element?.extras.toString())
        val opened = pubSub.published
            .filter { it.first == EmailOpened.CHANNEL }
            .takeLast(2)
            .map { Json.decodeFromString(EmailOpened.serializer(), it.second) }
        assertEquals(2, opened.size)
        assertNotEquals(opened[0].id, opened[1].id)
        assertEquals("m-4", opened.last().messageId)
        assertTrue(opened.all { it.id.startsWith("bml-") }, opened.toString())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun renderTracked(messageId: String?): bosca.bml.message.client.RenderResponse = runBlocking {
        bosca.bml.message.client.BmlMessageServerClient("http://localhost:$port").render(
            "tracked",
            "promo",
            bosca.bml.message.client.RenderRequest(
                messageId = messageId,
                recipientId = "r-1",
                unsubscribeUrl = "https://example.com/unsub",
            ),
            "service-jwt",
        )
    }

    private fun post(path: String, body: String, bearerToken: String? = "service-jwt"): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .apply { bearerToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to response.body()
    }

    companion object {
        private var port = 0
        private val registry = FakeRegistry()
        private lateinit var engine: NettyServerEngine
        private val client: HttpClient = HttpClient.newHttpClient()
        private val tracking = LinkTracking("test-secret", "http://public.example")
        private val analytics = RecordingAnalytics()
        private val pubSub = RecordingPubSub()
        private val lastGraphqlAuthorization = java.util.concurrent.atomic.AtomicReference<String>()
        private val graphqlServer: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/graphql") { exchange ->
                lastGraphqlAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.requestBody.readBytes()
                val body = """{"data":{"viewer":"service-account"}}""".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }

        @JvmStatic
        @BeforeClass
        fun boot() {
            graphqlServer.start()
            registry.start()
            registry.publish("acme", "1.0", buildMessageJar("one"))
            registry.publish("reloady", "1.0", buildMessageJar("reload-one"))
            registry.publish("guardrails", "1.0", buildGuardrailJar())
            registry.publish("tracked", "1.0", buildTrackedJar())
            registry.publish("pinny", "1.0", buildMessageJar("pin-one"))
            registry.publish("inliny", "1.0", buildBmlInlineJar())
            registry.publish("pinny", "2.0", buildMessageJar("pin-two"))
            registry.publish("payloady", "1.0", buildPayloadJar())
            registry.publish("graphqly", "1.0", buildGraphQLJar())
            registry.publish("dual-channel", "1.0", buildDualChannelJar())

            port = ServerSocket(0).use { it.localPort }
            val client = MessageArtifactClient(registry.url, token = null)
            val cache = MessageJarCache(File.createTempFile("bml-message-cache", "").apply { delete(); mkdirs() }, client)
            val projects = MessageProjects(cache, MessageServerRoutesTest::class.java.classLoader)
            val reloader = MessageReloader(projects, client, // NO seeds: warmup must DISCOVER every published project from the registry listing —
                // the restart-self-healing guarantee; the whole suite fails if discovery breaks.
                seeds = emptyList(), pollInterval = 1.seconds, pubSub = null)

            val app = BoscaApplication(
                ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: false\n".byteInputStream()),
            )
            RenderApi.install(
                app,
                projects,
                cache,
                publicBaseUrl = "http://public.example",
                renderTimeout = 2_000.milliseconds,
                tracking = tracking,
                graphqlClientFactory = bosca.bml.graphql.GraphQLClientFactory(
                    "http://localhost:${graphqlServer.address.port}/graphql",
                ),
            )
            AssetRoutes.install(app, cache)
            ProjectsApi.install(app, projects, client)
            TrackingRoutes.install(app, tracking, pubSub, analytics)
            HealthApi.install(app, projects, reloader)
            app.freezeMiddleware()
            runBlocking { reloader.warmup() }
            reloader.start()

            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-message-test-server") { engine.start() }
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).close(); return
                } catch (_: Exception) {
                    Thread.sleep(50)
                }
            }
            error("message server did not start on $port")
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
            registry.stop()
            graphqlServer.stop(0)
        }
    }
}
