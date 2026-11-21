package bosca.server.netty

import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagementServerTest {

    @Test
    fun `liveness supports HEAD and query strings and refuses other methods and paths`() {
        val server = server()
        try {
            val port = assertNotNull(server.start())
            HttpClient.newHttpClient().use { client ->
                val live = request(client, port, "GET", "/api/v1/live?probe=1")
                assertEquals(200, live.statusCode())
                assertEquals("""{"status":"live"}""", live.body())

                val head = request(client, port, "HEAD", "/api/v1/live")
                assertEquals(200, head.statusCode())
                assertEquals("", head.body())
                assertEquals(live.body().length.toString(), head.headers().firstValue("content-length").orElseThrow())

                val post = request(client, port, "POST", "/api/v1/live")
                assertEquals(404, post.statusCode())
                assertEquals("""{"status":"not found"}""", post.body())
                val wrongHead = request(client, port, "HEAD", "/api/v1/ready")
                assertEquals(404, wrongHead.statusCode())
                assertEquals("", wrongHead.body())
            }
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET /api/v1/live HTTP/1.1\r\nHost: localhost\r\nInvalid Header: value\r\n\r\n".toByteArray())
                val malformed = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(malformed.startsWith("HTTP/1.1 400"), malformed)
                assertTrue(malformed.endsWith("""{"status":"bad request"}"""), malformed)
            }
        } finally {
            server.stop()
            server.stop()
        }
    }

    @Test
    fun `stopping reports live even while an executor heartbeat is stalled`() {
        val stopping = AtomicBoolean(false)
        val server = ManagementServer(0, NioIoHandler.newFactory(), NioServerSocketChannel::class.java,
            listOf(WatchedExecutor("test executor", submit = { }, threads = { emptyList() })),
            stallTimeoutMillis = 10, isStopping = stopping::get)
        try {
            val port = assertNotNull(server.start())
            HttpClient.newHttpClient().use { client ->
                val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                var stalled = request(client, port, "GET", "/api/v1/live")
                while (stalled.statusCode() != 503 && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                    stalled = request(client, port, "GET", "/api/v1/live")
                }
                assertEquals(503, stalled.statusCode())
                assertTrue(stalled.body().contains("test executor"))
                val stalledHead = request(client, port, "HEAD", "/api/v1/live")
                assertEquals(503, stalledHead.statusCode())
                assertEquals("", stalledHead.body())

                stopping.set(true)
                val live = request(client, port, "GET", "/api/v1/live")
                assertEquals(200, live.statusCode())
                assertEquals("""{"status":"live"}""", live.body())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `bind failure is recoverable and stopping before a successful start is safe`() {
        ServerSocket(0).use { occupied ->
            val server = server(occupied.localPort)
            try {
                assertNull(server.start())
            } finally {
                server.stop()
            }
        }
        server().stop()
    }

    private fun server(port: Int = 0) = ManagementServer(port, NioIoHandler.newFactory(),
        NioServerSocketChannel::class.java, emptyList(), 100, isStopping = { false })

    private fun request(client: HttpClient, port: Int, method: String, path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString(),
    )
}
