package bosca.bml.server

import bosca.bml.i18n.MessageCatalog
import bosca.bml.i18n.MessageSource
import bosca.bml.project.CompiledProject
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlSharedCacheRevision
import bosca.bml.render.RenderContext
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

/** Verifies that localized shared shells are validated against their rendered catalog bytes. */
class BmlLocalizedSharedCacheTest {

    @BeforeTest
    fun reset() {
        message.set("Before")
        renders.set(0)
    }

    @Test
    fun `localized shared shell does not return a data-only 304`() {
        val first = get()
        val firstEtag = first.headers().firstValue("ETag").orElseThrow()
        assertEquals(200, first.statusCode())
        assertTrue("Before" in first.body(), first.body())

        message.set("After")
        val changed = get(firstEtag)

        assertEquals(200, changed.statusCode())
        assertTrue("After" in changed.body(), changed.body())
        assertNotEquals(firstEtag, changed.headers().firstValue("ETag").orElseThrow())
        assertEquals(2, renders.get())
    }

    private fun get(etag: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/localized")).GET()
        etag?.let { builder.header("If-None-Match", it) }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val message = AtomicReference("Before")
        private val renders = AtomicInteger()
        private var port = 0

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            val source = object : MessageSource {
                override suspend fun catalog(locale: Locale): MessageCatalog =
                    MessageCatalog(messages = mapOf("heading" to message.get()))
            }
            val page = object : BmlPageRenderer {
                override val route = "/localized"
                override val sharedCacheMaxAgeSeconds = 60L
                override suspend fun render(ctx: RenderContext) {
                    renders.incrementAndGet()
                    val heading = ctx.messages.catalog(ctx.locale).message("heading")
                    ctx.writer.markup("<html><body>$heading</body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject(name = "localized-cache-test", version = "1"),
                pages = listOf(page),
                port = port,
                dev = false,
                messageSource = source,
                sharedCacheRevisionProvider = {
                    BmlSharedCacheRevision("unchanged-data-revision")
                },
            )
            val app = BoscaApplication(
                ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    development: false\n".byteInputStream()),
            )
            server.install(app)
            app.freezeMiddleware()
            val engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-localized-cache-test-server") { engine.start() }
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).use { return }
                } catch (_: Exception) {
                    Thread.sleep(10)
                }
            }
            error("localized shared-cache test server did not start")
        }
    }
}
