package bosca.cli.harness.egress

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/** A loopback TCP server that echoes every connection's bytes until the peer closes. */
internal class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                Thread { socket.use { it.getInputStream().copyTo(it.getOutputStream(), 1024) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = server.close()
}
