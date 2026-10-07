package bosca.cli.images

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageRegistryTest {
    @Test
    fun `stable releases compare numerically and exclude aliases and prereleases`() {
        assertEquals("v6.100.2", latestStableTag(listOf("latest", "6.99.9", "v6.100.2", "7.0.0-rc1", "sha-123", "7.0.0-amd64")))
        assertEquals("6.100.2+build.1", latestStableTag(listOf("6.100.2+build.1", "6.9.20")))
        assertNull(latestStableTag(listOf("latest", "main", "7.0.0-rc1")))
    }

    @Test
    fun `bearer challenge and linked pages resolve a release from the final page`() = runBlocking {
        server { base, exchange ->
            when (exchange.requestURI.path) {
                "/token" -> {
                    assertTrue(exchange.requestURI.rawQuery.contains("scope=repository%3Abosca%2Fbosca-server%3Apull"))
                    exchange.reply(200, """{"access_token":"test-token"}""")
                }
                else -> if (exchange.requestHeaders.getFirst("Authorization") != "Bearer test-token") {
                    exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"$base/token\",service=\"registry\"")
                    exchange.reply(401, "{}")
                } else if (exchange.requestURI.query.contains("last=")) {
                    exchange.reply(200, """{"tags":["6.100.0","7.0.0-rc1"]}""")
                } else {
                    exchange.responseHeaders.add("Link", "</v2/bosca/bosca-server/tags/list?n=2&last=6.9.0>; rel=\"next\"")
                    exchange.reply(200, """{"tags":["6.9.0","latest"]}""")
                }
            }
        }.use { fixture ->
            assertEquals("6.100.0", ImageRegistry().latest("${fixture.base}/bosca/bosca-server"))
        }
    }

    @Test
    fun `Bosca basic authentication and pagination without links include all tags`() = runBlocking {
        val expected = "Basic " + Base64.getEncoder().encodeToString("api_token:secret".toByteArray())
        server { _, exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != expected) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Bosca Registry\"")
                exchange.reply(401, "{}")
            } else if (exchange.requestURI.query.contains("last=")) {
                assertTrue(exchange.requestURI.query.contains("last=1.0.999"))
                exchange.reply(200, """{"tags":["9.0.0"]}""")
            } else {
                exchange.reply(200, "{\"tags\":[${(0..999).joinToString { "\"1.0.$it\"" }}]}")
            }
        }.use { fixture ->
            assertEquals("9.0.0", ImageRegistry("api_token", "secret").latest("${fixture.base}/bosca/server"))
        }
    }

    @Test
    fun `failures and empty tag lists remain failures`() = runBlocking {
        for ((status, body) in listOf(404 to "{}", 200 to "{\"tags\":null}", 200 to "{\"tags\":[\"latest\"]}")) {
            server { _, exchange -> exchange.reply(status, body) }.use { fixture ->
                assertFailsWith<RuntimeException> { ImageRegistry().latest("${fixture.base}/bosca/server") }
            }
        }
    }

    @Test
    fun `pagination cannot send credentials to another registry`() = runBlocking<Unit> {
        server { _, exchange ->
            exchange.responseHeaders.add("Link", "<http://example.invalid/v2/bosca/server/tags/list>; rel=\"next\"")
            exchange.reply(200, "{\"tags\":[\"1.0.0\"]}")
        }.use { fixture ->
            assertFailsWith<IllegalArgumentException> { ImageRegistry().latest("${fixture.base}/bosca/server") }
        }
    }

    private class Fixture(val server: HttpServer) : AutoCloseable {
        val base = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun server(handler: (String, HttpExchange) -> Unit): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = Fixture(server)
        server.createContext("/") { exchange -> handler(fixture.base, exchange) }
        server.start()
        return fixture
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
