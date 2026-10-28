package bosca.atlas.server

import bosca.server.netty.NettyServerEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns Atlas's backend and optional UI listener; either listener stopping shuts down the host. */
internal class AtlasServer(
    private val api: NettyServerEngine,
    private val createUi: ((Int) -> NettyServerEngine)? = null,
) {
    fun start() {
        val stopping = AtomicBoolean()
        val finished = CountDownLatch(1)
        val shutdownHook = Thread({
            stopping.set(true)
            finished.await()
        }, "atlas-shutdown")
        val executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("atlas-listener-", 0).factory())
        val completions = ExecutorCompletionService<Unit>(executor)
        var ui: NettyServerEngine? = null
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            if (startListener(api, completions) && !stopping.get() && createUi != null) {
                ui = createUi(requireNotNull(api.boundPort))
                startListener(ui, completions)
            }
            while (!stopping.get()) {
                val completed = completions.poll(100, TimeUnit.MILLISECONDS) ?: continue
                completed.result()
                break
            }
        } finally {
            try {
                // Drain UI requests while the API is still available to their HTTP proxies.
                try {
                    ui?.stopWithoutHalting()
                } finally {
                    api.stopWithoutHalting()
                }
            } finally {
                executor.shutdown()
                finished.countDown()
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook)
                } catch (_: IllegalStateException) {
                    // The JVM already owns the hook during process shutdown.
                }
            }
        }
    }

    private fun startListener(engine: NettyServerEngine, completions: ExecutorCompletionService<Unit>): Boolean {
        val started = completions.submit { engine.start() }
        // Complete the bind before allowing shutdown to stop this engine.
        while (engine.boundPort == null && !started.isDone) Thread.sleep(10)
        if (started.isDone) {
            started.result()
            return false
        }
        return true
    }

    private fun Future<Unit>.result() {
        try {
            get()
        } catch (failure: ExecutionException) {
            throw requireNotNull(failure.cause)
        }
    }
}
