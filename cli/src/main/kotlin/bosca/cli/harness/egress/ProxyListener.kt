package bosca.cli.harness.egress

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A TCP listener whose accept loop and connections are children of the scope that started it.
 * Each accepted connection is handled concurrently and closed when its handler returns.
 */
internal class ProxyListener private constructor(private val selector: SelectorManager, private val server: ServerSocket, private val job: Job) : AutoCloseable {
    /** The bound local port. */
    val port: Int get() = server.port

    /** Suspends until the listener stops. */
    suspend fun join() = job.join()

    override fun close() {
        job.cancel()
        server.close()
        selector.close()
    }

    companion object {
        /** Binds [host]:[port] (0 selects an ephemeral port) and serves connections with [handler] in [scope]. */
        suspend fun start(scope: CoroutineScope, name: String, host: String, port: Int, handler: suspend (Socket, SelectorManager) -> Unit): ProxyListener {
            val selector = SelectorManager(Dispatchers.IO)
            val server = try { aSocket(selector).tcp().bind(host, port) } catch (failure: Throwable) { selector.close(); throw failure }
            val job = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val socket = server.accept()
                        launch {
                            try { handler(socket, selector) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { System.err.println("$name connection ended: ${failure::class.simpleName}: ${failure.message}") }
                            finally { socket.close() }
                        }
                    }
                } finally { server.close() }
            }
            return ProxyListener(selector, server, job)
        }
    }
}
