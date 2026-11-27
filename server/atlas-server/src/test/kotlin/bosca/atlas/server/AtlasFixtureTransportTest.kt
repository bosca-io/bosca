package bosca.atlas.server

import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.channels.ServerSocketChannel
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AtlasFixtureTransportTest {
    @Test fun `port selection skips an occupied IPv4 loopback address`() = occupied(StandardProtocolFamily.INET, "127.0.0.1")

    @Test fun `port selection skips an occupied IPv6 loopback address`() = occupied(StandardProtocolFamily.INET6, "::1")

    private fun occupied(family: StandardProtocolFamily, host: String) {
        ServerSocketChannel.open(family).use { listener ->
            listener.bind(InetSocketAddress(host, 0))
            val occupied = (listener.localAddress as InetSocketAddress).port
            var attempts = 0
            val selected = atlasFixturePort { if (attempts++ == 0) occupied else 0 }
            assertNotEquals(occupied, selected)
            assertTrue(attempts >= 2)
            for ((protocol, address) in listOf(StandardProtocolFamily.INET to "127.0.0.1", StandardProtocolFamily.INET6 to "::1")) {
                ServerSocketChannel.open(protocol).use { it.bind(InetSocketAddress(address, selected)) }
            }
        }
    }

    @Test fun `response deadline cancels a body whose keepalive headers lack framing`() {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val port = (server.localAddress as InetSocketAddress).port
            val release = CountDownLatch(1)
            val headersSent = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val serving = executor.submit {
                server.accept().use { connection ->
                    val socket = connection.socket()
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n\r\nPartial body".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            try {
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/ready"))
                    .timeout(Duration.ofSeconds(5)).build()
                val error = assertFailsWith<HttpTimeoutException> { atlasFixtureResponse(client, request, Duration.ofMillis(500)) }
                assertTrue(error.message.orEmpty().contains("Timed out reading"))
                assertTrue(error.cause is java.util.concurrent.TimeoutException)
                assertTrue(headersSent.await(1, TimeUnit.SECONDS))
            } finally {
                client.shutdownNow()
                release.countDown()
                server.close()
                try { serving.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
            }
        }
    }
}
