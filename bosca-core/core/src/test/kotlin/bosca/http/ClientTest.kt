package bosca.http

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientTest {

    @Test
    fun clientCanBeInstantiated() {
        val client = Client()
        assertNotNull(client)
    }

    @Test
    fun `download writes a successful response to a temporary file`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val file = Client().download("http://127.0.0.1:${server.address.port}/file", "bin")
            try {
                assertTrue(file.name.endsWith(".bin"))
                assertContentEquals(bytes, file.readBytes())
            } finally {
                file.delete()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `download reports unsuccessful HTTP responses`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val failure = assertFailsWith<IOException> {
                Client().download("http://127.0.0.1:${server.address.port}/missing", "txt")
            }
            assertTrue(failure.message.orEmpty().contains("404"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `download propagates asynchronous connection failures`() = runTest {
        val port = ServerSocket(0).use { it.localPort }

        assertFailsWith<IOException> {
            Client().download("http://127.0.0.1:$port/unavailable", "txt")
        }
    }
}
