package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlDeferredRenderer
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
import kotlin.test.assertTrue

/** Covers deferred rendering for sites that rely on compiler-inlined component styles. */
class BmlDeferredInlineStylesTest {

    @Test
    fun `page keeps inline component styles when only global CSS is linked`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/inline")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode())
        assertTrue("<style>.shell-card{display:block}</style>" in response.body(), response.body())
        assertTrue("/_bml/app.css?_ts=" in response.body(), response.body())
    }

    @Test
    fun `deferred fragment inlines component styles when only global CSS is linked`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/deferred/InlinePage%3Aprivate"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"props":{},"page":"/inline","path":"/inline","query":{}}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode())
        assertTrue("<style>.private-card{color:red}</style>" in response.body(), response.body())
        assertTrue("<div class=\"private-card\">Private</div>" in response.body(), response.body())
    }

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine
        private val client = HttpClient.newHttpClient()

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            val deferred = object : BmlDeferredRenderer {
                override val id = "InlinePage:private"
                override val pageRoute = "/inline"

                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                    if (ctx.useStyleOnce("private-card")) {
                        ctx.writer.markup("<style>.private-card{color:red}</style>")
                    }
                    ctx.writer.markup("<div class=\"private-card\">Private</div>")
                }
            }
            val page = object : BmlPageRenderer {
                override val route = "/inline"
                override val deferredRenderers = listOf(deferred)
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><head></head><body>")
                    if (ctx.useStyleOnce("shell-card")) {
                        ctx.writer.markup("<style>.shell-card{display:block}</style>")
                    }
                    ctx.writer.markup("<div class=\"shell-card\">Shell</div></body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject(name = "inline-deferred-test", version = "1"),
                pages = listOf(page),
                port = port,
                dev = false,
                globalCss = "body{margin:0}",
            )
            val app = BoscaApplication(
                ApplicationConfig.load(
                    "bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: false\n".byteInputStream(),
                ),
            )
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-deferred-inline-styles-test-server") { engine.start() }
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(50)
                }
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
