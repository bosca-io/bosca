package bosca.git.server

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import java.net.ServerSocket
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitServerLifecycleTest {
    private fun engine(port: Int = 0, stopped: AtomicInteger): NettyServerEngine {
        val app = BoscaApplication(ApplicationConfig.load(
            "bosca:\n  server:\n    drain-timeout-ms: 0\n".byteInputStream()), ownsServices = false)
        app.onShutdown { stopped.incrementAndGet() }
        return NettyServerEngine(app, port)
    }

    private fun launch(server: GitServer): FutureTask<Unit> = FutureTask<Unit> { server.start() }.also {
        thread(isDaemon = true) { it.run() }
    }

    private fun awaitBind(engine: NettyServerEngine) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (engine.boundPort == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(engine.boundPort != null, "Listener did not bind")
    }

    private fun assertPortReleased(engine: NettyServerEngine) {
        ServerSocket(requireNotNull(engine.boundPort)).use { assertTrue(it.isBound) }
    }

    @Test
    fun `UI bind failure stops API and both application lifecycles`() {
        ServerSocket(0).use { occupied ->
            val apiStops = AtomicInteger()
            val uiStops = AtomicInteger()
            val api = engine(stopped = apiStops)
            val task = launch(GitServer(api) { engine(occupied.localPort, uiStops) })
            val failure = assertFailsWith<ExecutionException> { task.get(20, TimeUnit.SECONDS) }
            assertIs<java.net.BindException>(failure.cause)
            assertEquals(1, apiStops.get())
            assertEquals(1, uiStops.get())
            assertPortReleased(api)
        }
    }

    @Test
    fun `API bind failure never starts UI`() {
        ServerSocket(0).use { occupied ->
            val stopped = AtomicInteger()
            var uiCreated = false
            val task = launch(GitServer(engine(occupied.localPort, stopped)) {
                uiCreated = true
                error("UI must not start")
            })
            val failure = assertFailsWith<ExecutionException> { task.get(20, TimeUnit.SECONDS) }
            assertIs<java.net.BindException>(failure.cause)
            assertEquals(false, uiCreated)
            assertEquals(1, stopped.get())
        }
    }

    @Test
    fun `UI installation failure stops API`() {
        val stopped = AtomicInteger()
        val api = engine(stopped = stopped)
        val task = launch(GitServer(api) { error("UI installation failed") })
        val failure = assertFailsWith<ExecutionException> { task.get(20, TimeUnit.SECONDS) }
        assertEquals("UI installation failed", failure.cause?.message)
        assertEquals(1, stopped.get())
        assertPortReleased(api)
    }

    @Test
    fun `closing UI stops API and releases both ports`() {
        val apiStops = AtomicInteger()
        val uiStops = AtomicInteger()
        val api = engine(stopped = apiStops)
        val ui = engine(stopped = uiStops)
        val task = launch(GitServer(api) { ui })
        try {
            awaitBind(ui)
            ui.stopWithoutHalting()
            task.get(20, TimeUnit.SECONDS)
            assertEquals(1, apiStops.get())
            assertEquals(1, uiStops.get())
            assertPortReleased(api)
            assertPortReleased(ui)
        } finally {
            ui.stopWithoutHalting()
            api.stopWithoutHalting()
        }
    }
}
