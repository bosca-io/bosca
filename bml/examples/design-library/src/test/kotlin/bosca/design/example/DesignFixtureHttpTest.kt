package bosca.design.example

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesignFixtureHttpTest {
    @Test
    fun `complete responses retain status headers and body`() {
        withServer({ exchange, _ ->
            val content = "A complete response · ✓".toByteArray()
            exchange.responseHeaders.add("X-Fixture", "complete")
            exchange.sendResponseHeaders(201, content.size.toLong())
            exchange.responseBody.write(content)
        }) { uri, _, _ ->
            val response = requestDesignFixture(HttpClient.newHttpClient(), request(uri))
            assertEquals(201, response.statusCode())
            assertEquals("complete", response.headers().firstValue("X-Fixture").orElseThrow())
            assertEquals("A complete response · ✓", response.body())
        }
    }

    @Test
    fun `deadline covers missing headers`() {
        assertDeadline(sendHeaders = false)
    }

    @Test
    fun `deadline covers an unfinished body after headers arrive`() {
        assertDeadline(sendHeaders = true)
    }

    @Test
    fun `truncated bodies retain their transport failure`() {
        withServer({ exchange, _ ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write('a'.code)
            exchange.responseBody.flush()
        }) { uri, _, _ ->
            assertFailsWith<IOException> {
                requestDesignFixture(HttpClient.newHttpClient(), request(uri), Duration.ofSeconds(2))
            }
        }
    }

    @Test
    fun `interrupting the caller stops its pending response wait`() {
        withServer({ exchange, release ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write('a'.code)
            exchange.responseBody.flush()
            release.await(3, TimeUnit.SECONDS)
        }) { uri, entered, _ ->
            val failure = AtomicReference<Throwable>()
            val caller = Thread.ofPlatform().daemon().start {
                try {
                    requestDesignFixture(HttpClient.newHttpClient(), request(uri))
                } catch (caught: Throwable) {
                    failure.set(caught)
                }
            }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                caller.interrupt()
                caller.join(2_000)
                assertFalse(caller.isAlive)
                assertIs<InterruptedException>(failure.get())
            } finally {
                caller.interrupt()
                caller.join(2_000)
            }
        }
    }

    private fun request(uri: URI): HttpRequest = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()

    private fun assertDeadline(sendHeaders: Boolean) {
        withServer({ exchange, release ->
            if (sendHeaders) {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write('a'.code)
                exchange.responseBody.flush()
            }
            release.await(3, TimeUnit.SECONDS)
        }) { uri, entered, _ ->
            val failure = assertFailsWith<AssertionError> {
                requestDesignFixture(HttpClient.newHttpClient(), request(uri), Duration.ofMillis(750))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS), "The request reached the controlled server")
            assertTrue(failure.message.orEmpty().contains("GET $uri"), failure.message)
            assertTrue(failure.message.orEmpty().contains("complete HTTP response"), failure.message)
            assertIs<TimeoutException>(failure.cause)
        }
    }

    private fun withServer(
        respond: (HttpExchange, CountDownLatch) -> Unit,
        test: (URI, CountDownLatch, CountDownLatch) -> Unit,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                entered.countDown()
                respond(exchange, release)
            }
        }
        server.start()
        try {
            test(URI("http://127.0.0.1:${server.address.port}/deadline"), entered, release)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
