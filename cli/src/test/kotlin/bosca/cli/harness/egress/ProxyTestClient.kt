package bosca.cli.harness.egress

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

/** A blocking test client speaking raw HTTP/1.1 to a proxy listener. */
internal class ProxyTestClient(port: Int) : AutoCloseable {
    val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 }

    fun send(text: String) = socket.getOutputStream().run { write(text.toByteArray(Charsets.ISO_8859_1)); flush() }

    /** Reads one response head, without its terminating blank line. */
    fun readHead(): String {
        val bytes = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (!bytes.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            val value = input.read()
            check(value >= 0) { "Connection closed before the response head: ${bytes.toString(Charsets.ISO_8859_1)}" }
            bytes.write(value)
        }
        return bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
    }

    fun read(count: Int): String = socket.getInputStream().readNBytes(count).toString(Charsets.ISO_8859_1)

    /** True when the peer closed the connection. */
    fun closedByPeer(): Boolean = socket.getInputStream().read() == -1

    override fun close() = socket.close()
}
