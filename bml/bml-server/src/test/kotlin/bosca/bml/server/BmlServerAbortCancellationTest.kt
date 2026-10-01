package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.project.DiscoveryFiles
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.bml.graphql.HttpGraphQLClient
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * Proves the browser-abort story over real TCP: when the user clicks away mid-load (the browser
 * closes the socket of the in-flight navigation), the abandoned page render must be CANCELLED
 * promptly — including an upstream HTTP fetch it is suspended on (the SSR → GraphQL hop) — not
 * left running to completion for a response nobody will read. Fast clicking otherwise stacks
 * zombie renders behind the live one.
 */
class BmlServerAbortCancellationTest {

    @Test fun `client abort cancels the in-flight page render promptly`() {
        abortMidRender("/slow", slowStarted)
        assertTrue(
            slowCancelled.await(3, TimeUnit.SECONDS),
            "the page render kept running after its client aborted — abandoned renders are not cancelled",
        )
    }

    @Test fun `client abort propagates through the render's upstream HTTP call`() {
        abortMidRender("/fetching", upstreamStarted)
        assertTrue(
            upstreamCancelled.await(3, TimeUnit.SECONDS),
            "the upstream fetch kept running after the page's client aborted — cancellation is not propagating through await()",
        )
    }

    /** Sends a keep-alive GET (what a browser navigation sends), then closes the socket once [started] confirms the render is in flight. */
    private fun abortMidRender(path: String, started: CountDownLatch) {
        val socket = Socket("localhost", port)
        socket.getOutputStream().let {
            it.write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            it.flush()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "$path render never started")
        socket.close() // the user clicked something else
    }

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine

        private val slowStarted = CountDownLatch(1)
        private val slowCancelled = CountDownLatch(1)
        private val upstreamStarted = CountDownLatch(1)
        private val upstreamCancelled = CountDownLatch(1)

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            // A render that parks mid-page, recording whether the abort reaches it.
            val slowPage = object : BmlPageRenderer {
                override val route = "/slow"
                override suspend fun render(ctx: RenderContext) {
                    slowStarted.countDown()
                    try {
                        delay(30_000)
                        ctx.writer.markup("<html><body>done</body></html>")
                    } catch (e: CancellationException) {
                        slowCancelled.countDown()
                        throw e
                    }
                }
            }
            // The stand-in "GraphQL API": a slow POST endpoint on the same engine (a sliver
            // renderer, since the GraphQL client POSTs).
            val slowUpstream = bosca.bml.render.BmlComponentRenderer { _, _, _ ->
                upstreamStarted.countDown()
                try {
                    delay(30_000)
                } catch (e: CancellationException) {
                    upstreamCancelled.countDown()
                    throw e
                }
            }
            // The page under test: suspended on an upstream fetch via the exact GraphQL client an
            // SSR page uses to reach the data plane (the endpoint here is the slow sliver above —
            // the render is cancelled long before any response would need parsing).
            val upstreamGql = HttpGraphQLClient(endpoint = "http://localhost:$port/_bml/render/slowdata")
            val fetchingPage = object : BmlPageRenderer {
                override val route = "/fetching"
                override suspend fun render(ctx: RenderContext) {
                    upstreamGql.execute("query { x }", null, null, null)
                    ctx.writer.markup("<html><body>rendered</body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject(
                    name = "t", version = "0.0.1",
                    discovery = DiscoveryFiles(sitemap = "", robots = "", llms = ""),
                ),
                pages = listOf(slowPage, fetchingPage),
                port = port,
                componentRenderers = mapOf("slowdata" to slowUpstream),
            )
            val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream()))
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-abort-test-server") { engine.start() }
            repeat(100) {
                try { Socket("localhost", port).close(); return } catch (_: Exception) { Thread.sleep(50) }
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
