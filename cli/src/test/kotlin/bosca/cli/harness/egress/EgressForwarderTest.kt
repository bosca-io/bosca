package bosca.cli.harness.egress

import kotlinx.coroutines.*
import java.net.InetAddress
import kotlin.test.*

class EgressForwarderTest {
    private val credentials = EgressCredentials("0b5f8f2e-5c0a-4d55-9a49-1b6f6d1c2e3f", "egress-token-fixture-value")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loopback = EgressResolver { listOf(InetAddress.getLoopbackAddress()) }

    @AfterTest fun cleanup() = scope.cancel()

    private fun forwarder(port: Int) = runBlocking { EgressForwarder(EgressDestination("kit-egress", port), credentials, loopback).start(scope) }

    @Test fun `the forwarder replaces client credentials with the runtime's and relays the tunnel`() {
        FakeUpstreamProxy("HTTP/1.1 200 Connection Established\r\nVia: fixture\r\n\r\n").use { upstream ->
            forwarder(upstream.port).use { listener ->
                ProxyTestClient(listener.port).use { client ->
                    client.send("CONNECT API.example.com:443 HTTP/1.1\r\nHost: attacker.test\r\nUser-Agent: fixture\r\nProxy-Authorization: Basic c3RvbGVu\r\n\r\nearly")
                    assertEquals("HTTP/1.1 200 Connection Established\r\nVia: fixture", client.readHead())
                    assertEquals("early", client.read(5))
                    client.send("ping")
                    assertEquals("ping", client.read(4))
                }
                val head = upstream.heads.single().split("\r\n")
                assertEquals("CONNECT api.example.com:443 HTTP/1.1", head.first())
                assertEquals(listOf("Proxy-Authorization: ${credentials.proxyAuthorization()}"), head.filter { it.startsWith("Proxy-Authorization", ignoreCase = true) })
                assertEquals(listOf("Host: api.example.com:443"), head.filter { it.startsWith("Host:", ignoreCase = true) })
                assertContains(head, "User-Agent: fixture")
                assertFalse(upstream.heads.single().contains("c3RvbGVu"))
            }
        }
    }

    @Test fun `upstream refusals pass through to the client`() {
        FakeUpstreamProxy("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { upstream ->
            forwarder(upstream.port).use { listener ->
                ProxyTestClient(listener.port).use { client ->
                    client.send("CONNECT blocked.example.com:443 HTTP/1.1\r\n\r\n")
                    assertEquals("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close", client.readHead())
                    assertTrue(client.closedByPeer())
                }
            }
        }
    }

    @Test fun `non-CONNECT and malformed requests never reach the egress proxy`() {
        FakeUpstreamProxy("HTTP/1.1 200 Connection Established\r\n\r\n").use { upstream ->
            forwarder(upstream.port).use { listener ->
                ProxyTestClient(listener.port).use { client ->
                    client.send("GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n")
                    assertEquals("HTTP/1.1 405 Method Not Allowed", client.readHead().lineSequence().first())
                }
                ProxyTestClient(listener.port).use { client ->
                    client.send("CONNECT example.com:443\r\nInjected: header HTTP/1.1\r\n\r\n")
                    assertEquals("HTTP/1.1 400 Bad Request", client.readHead().lineSequence().first())
                }
                assertTrue(upstream.heads.isEmpty())
            }
        }
    }

    @Test fun `an unavailable egress proxy is a bad gateway`() {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        forwarder(closed).use { listener ->
            ProxyTestClient(listener.port).use { client ->
                client.send("CONNECT example.com:443 HTTP/1.1\r\n\r\n")
                assertEquals("HTTP/1.1 502 Bad Gateway", client.readHead().lineSequence().first())
            }
        }
    }

    @Test fun `the forwarder reaches deployment-trusted destinations through the egress proxy`() {
        EchoServer().use { echo ->
            val proxy = runBlocking {
                EgressProxy({ EgressLookupResult.Denied }, setOf(EgressDestination("bosca.test", echo.port)), { listOf(InetAddress.getLoopbackAddress()) }).start(scope, "127.0.0.1", 0)
            }
            proxy.use {
                forwarder(proxy.port).use { listener ->
                    ProxyTestClient(listener.port).use { client ->
                        client.send("CONNECT bosca.test:${echo.port} HTTP/1.1\r\n\r\n")
                        assertEquals("HTTP/1.1 200 Connection Established", client.readHead())
                        client.send("through")
                        assertEquals("through", client.read(7))
                    }
                    ProxyTestClient(listener.port).use { client ->
                        client.send("CONNECT elsewhere.test:443 HTTP/1.1\r\n\r\n")
                        assertEquals("HTTP/1.1 403 Forbidden", client.readHead().lineSequence().first())
                    }
                }
            }
        }
    }
}
