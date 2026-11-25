package bosca.cli.harness.egress

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A loopback stand-in for the egress proxy: it records each request head, answers with [response]
 * and, when that response is a 200, echoes the tunnelled bytes.
 */
internal class FakeUpstreamProxy(private val response: String) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val heads = CopyOnWriteArrayList<String>()

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                Thread {
                    socket.use {
                        val input = it.getInputStream()
                        val head = ByteArrayOutputStream()
                        while (!head.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                            val value = input.read()
                            if (value < 0) return@use
                            head.write(value)
                        }
                        heads += head.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
                        it.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                        it.getOutputStream().flush()
                        if (response.startsWith("HTTP/1.1 200")) input.copyTo(it.getOutputStream(), 1024)
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = server.close()
}
