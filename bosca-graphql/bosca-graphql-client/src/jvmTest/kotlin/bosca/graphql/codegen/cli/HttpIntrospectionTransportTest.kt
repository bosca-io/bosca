package bosca.graphql.codegen.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives the real JDK-HTTP introspection transport against a loopback server: success, headers, and a non-2xx failure. */
class HttpIntrospectionTransportTest {

    @Test
    fun `posts over HTTP, forwards headers, returns the body, and fails on a non-2xx status`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val receivedBody = AtomicReference<String>()
        val receivedAuth = AtomicReference<String?>()

        server.createContext("/graphql") { exchange ->
            receivedBody.set(exchange.requestBody.readBytes().decodeToString())
            receivedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = """{"data":{"__schema":{"types":[]}}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/bad") { exchange ->
            val bytes = "boom".encodeToByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val body = HttpIntrospectionTransport.post(
                "$base/graphql",
                """{"query":"introspect"}""",
                mapOf("Authorization" to "Bearer t"),
            )
            assertTrue("__schema" in body, body)
            assertTrue("introspect" in receivedBody.get(), receivedBody.get())
            assertEquals("Bearer t", receivedAuth.get())

            val failure = assertFailsWith<IllegalStateException> {
                HttpIntrospectionTransport.post("$base/bad", "{}", emptyMap())
            }
            assertTrue("503" in failure.message!!, failure.message!!)
        } finally {
            server.stop(0)
        }
    }
}
