package bosca.bml.server

import bosca.bml.project.CompiledProject
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

class BmlProductionClientOnlyPageTest {

    @Test
    fun `production page with client code and no CSS or components loads its page bundle`() {
        val page = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, page.statusCode())
        assertTrue("/_bml/js/index.page.js?_ts=" in page.body(), page.body())
        assertTrue("/_bml/js/Page.js" !in page.body(), page.body())

        val bundle = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/js/index.page.js")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, bundle.statusCode())
        assertEquals("console.log('page only')", bundle.body())
    }

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine
        private lateinit var clientDir: java.io.File
        private val client = HttpClient.newHttpClient()

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            clientDir = java.nio.file.Files.createTempDirectory("bml-client-only-production-test").toFile()
                .also { it.resolve("index.page.js").writeText("console.log('page only')") }
            val page = object : BmlPageRenderer {
                override val route = "/"
                override val clientModule = "Page.js"
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><head></head><body>client page</body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject("test", "1"),
                pages = listOf(page),
                clientDir = clientDir,
                port = port,
                dev = false,
            )
            val app = BoscaApplication(
                ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n".byteInputStream()),
            )
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-client-only-production-test-server") { engine.start() }
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
            clientDir.deleteRecursively()
        }
    }
}
