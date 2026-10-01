package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlLocalePolicy
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

/** A shared page whose locale policy fails must reach BML's error handling, never a bare framework error. */
class BmlSharedLocaleFailureTest {

    @Test
    fun `a failing locale policy on a shared page is answered privately by the error path`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/shared")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(500, response.statusCode())
        // BML's own fallback (the error page also needs the failing locale policy).
        assertEquals("500 Internal Server Error", response.body())
        assertEquals(listOf("private, no-store"), response.headers().allValues("Cache-Control"))
        assertEquals(emptyList(), response.headers().allValues("Cloudflare-CDN-Cache-Control"))
    }

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            val shared = object : BmlPageRenderer {
                override val route = "/shared"
                override val sharedCacheMaxAgeSeconds = 60L
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>shared</body></html>")
                }
            }
            val error = object : BmlPageRenderer {
                override val route = "/error"
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>error page</body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject("locale-failure-test", "1"),
                pages = listOf(shared, error),
                port = port,
                dev = false,
                errorRoute = "/error",
                localePolicy = BmlLocalePolicy { error("localization project unavailable") },
            )
            val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n".byteInputStream()))
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-locale-failure-test-server") { engine.start() }
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
