package bosca.cli.harness.egress

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class EgressProxyTest {
    private val session = "0b5f8f2e-5c0a-4d55-9a49-1b6f6d1c2e3f"
    private val token = "egress-token-fixture-value"
    private val credentials = EgressCredentials(session, token)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val echo = EchoServer()
    private val lookups = CopyOnWriteArrayList<Pair<String?, String?>>()
    private val lookup = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/api/v1/kit/egress") { exchange ->
            val sessionHeader = exchange.requestHeaders.getFirst("X-Bosca-Kit-Session")
            val tokenHeader = exchange.requestHeaders.getFirst("X-Bosca-Kit-Egress")
            lookups += sessionHeader to tokenHeader
            val (status, body) = when {
                tokenHeader == "outage-token" -> 500 to ""
                sessionHeader != session || tokenHeader != token -> 404 to ""
                else -> 200 to """{"destinations":["public.test:${echo.port}","private.test:${echo.port}","not-a-destination:"]}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val loopback = InetAddress.getLoopbackAddress().address
    private val resolver = EgressResolver { host ->
        when (host) {
            "bosca.test", "public.test", "private.test" -> listOf(InetAddress.getByAddress(host, loopback))
            else -> throw java.net.UnknownHostException(host)
        }
    }
    // The fixture treats "public.test" as public so an approved tunnel can reach the loopback echo server.
    private val proxy = EgressProxy(EgressLookupCache(BoscaEgressLookup("http://127.0.0.1:${lookup.address.port}/")),
        setOf(EgressDestination("bosca.test", echo.port)), resolver,
        isPublic = { it.hostName == "public.test" || EgressAddresses.isPublic(it) }, headTimeout = 2.seconds)
    private val listener = runBlocking { proxy.start(scope, "127.0.0.1", 0) }

    @AfterTest fun cleanup() {
        listener.close(); scope.cancel(); lookup.stop(0); echo.close()
    }

    private fun connect(target: String, authorization: String? = credentials.proxyAuthorization(), extra: String = ""): Pair<ProxyTestClient, String> {
        val client = ProxyTestClient(listener.port)
        client.send("CONNECT $target HTTP/1.1\r\nHost: $target\r\n" + (authorization?.let { "Proxy-Authorization: $it\r\n" } ?: "") + "\r\n" + extra)
        return client to client.readHead()
    }

    private fun status(target: String, authorization: String? = credentials.proxyAuthorization()): String =
        connect(target, authorization).let { (client, head) -> client.use { head.lineSequence().first() } }

    @Test fun `deployment-trusted destinations relay bytes without a lookup or the public-address check`() {
        val (client, head) = connect("bosca.test:${echo.port}", extra = "early")
        client.use {
            assertEquals("HTTP/1.1 200 Connection Established", head)
            assertEquals("early", client.read(5), "Bytes sent before the tunnel opened are relayed")
            client.send("ping")
            assertEquals("ping", client.read(4))
        }
        assertTrue(lookups.isEmpty())
    }

    @Test fun `approved destinations relay bytes and lookups are cached per session`() {
        repeat(2) {
            val (client, head) = connect("PUBLIC.test.:${echo.port}")
            client.use {
                assertEquals("HTTP/1.1 200 Connection Established", head)
                client.send("hello")
                assertEquals("hello", client.read(5))
            }
        }
        assertEquals(listOf(session to token), lookups.toList())
    }

    @Test fun `approved destinations resolving to private addresses are forbidden`() {
        assertEquals("HTTP/1.1 403 Forbidden", status("private.test:${echo.port}"))
    }

    @Test fun `destinations outside the lookup and denied lookups are forbidden and outages are bad gateways`() {
        assertEquals("HTTP/1.1 403 Forbidden", status("public.test:1"))
        assertEquals("HTTP/1.1 403 Forbidden", status("other.test:443"))
        assertEquals("HTTP/1.1 403 Forbidden", status("bosca.test:443"), "Trusted destinations match their port")
        assertEquals("HTTP/1.1 403 Forbidden", status("public.test:${echo.port}", credentials.copy(token = "wrong-token-value").proxyAuthorization()))
        assertEquals("HTTP/1.1 502 Bad Gateway", status("public.test:${echo.port}", credentials.copy(token = "outage-token").proxyAuthorization()))
    }

    @Test fun `missing or malformed proxy credentials require authentication`() {
        for (authorization in listOf(null, "Basic " + Base64.getEncoder().encodeToString("not-a-session:$token".toByteArray()), "Bearer $token")) {
            val (client, head) = connect("bosca.test:${echo.port}", authorization)
            client.use {
                assertEquals("HTTP/1.1 407 Proxy Authentication Required", head.lineSequence().first())
                assertContains(head, "Proxy-Authenticate: Basic realm=\"bosca-kit\"")
                assertTrue(client.closedByPeer())
            }
        }
        assertTrue(lookups.isEmpty())
    }

    @Test fun `only CONNECT is supported and malformed requests are rejected`() {
        ProxyTestClient(listener.port).use { client ->
            client.send("GET http://bosca.test/ HTTP/1.1\r\nHost: bosca.test\r\n\r\n")
            val head = client.readHead()
            assertEquals("HTTP/1.1 405 Method Not Allowed", head.lineSequence().first())
            assertContains(head, "Allow: CONNECT")
        }
        for (request in listOf("CONNECT\r\n\r\n", "CONNECT bosca.test:443 HTTP/2\r\n\r\n", "CONNECT bosca.test:443 HTTP/1.1\r\nBad Header\r\n\r\n")) {
            ProxyTestClient(listener.port).use { client ->
                client.send(request)
                assertEquals("HTTP/1.1 400 Bad Request", client.readHead().lineSequence().first(), request)
            }
        }
        for (target in listOf("*.test:443", "[::1]:443", "bosca.test:0")) assertEquals("HTTP/1.1 400 Bad Request", status(target))
        ProxyTestClient(listener.port).use { client ->
            client.send("CONNECT bosca.test:443 HTTP/1.1\r\nX-Padding: " + "a".repeat(MAX_PROXY_HEAD_BYTES) + "\r\n\r\n")
            assertEquals("HTTP/1.1 431 Request Header Fields Too Large", client.readHead().lineSequence().first())
        }
        ProxyTestClient(listener.port).use { client ->
            client.send("CONNECT bosca.test:${echo.port} HTTP/1.1\r\n")
            assertEquals("HTTP/1.1 408 Request Timeout", client.readHead().lineSequence().first(), "An incomplete head times out")
        }
    }

    @Test fun `closing either side closes the tunnel`() {
        val (client, head) = connect("bosca.test:${echo.port}")
        client.use {
            assertEquals("HTTP/1.1 200 Connection Established", head)
            client.send("x"); assertEquals("x", client.read(1))
            client.socket.shutdownOutput()
            assertTrue(client.closedByPeer(), "The proxy closes the client once the upstream side closes")
        }
    }
}
