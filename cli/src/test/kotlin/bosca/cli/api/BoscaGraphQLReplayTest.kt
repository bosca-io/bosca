package bosca.cli.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Count server-accepted operations rather than assuming a single client call means a single POST. */
class BoscaGraphQLReplayTest {
    @Test fun `redirects require a canonical endpoint rather than resending the POST`(): Unit = runBlocking {
        val redirected = AtomicInteger()
        val saved = AtomicInteger()
        val http = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use {
            exchange.requestBody.readAllBytes()
            if (exchange.requestURI.path == "/graphql") {
                redirected.incrementAndGet()
                exchange.responseHeaders.add("Location", "/canonical")
                exchange.sendResponseHeaders(307, -1)
            } else {
                saved.incrementAndGet()
                val bytes = """{"data":{"saved":true}}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            }
        } }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val redirectedClient = BoscaGraphQLClient("$base/graphql", http, { "bsk_fixture" }, { null })
            assertEquals(307, assertFailsWith<BoscaHttpException> { redirectedClient.execute("mutation { start }", null, null) }.statusCode)
            assertEquals(1, redirected.get()); assertEquals(0, saved.get())
            val canonicalClient = BoscaGraphQLClient("$base/canonical", http, { "bsk_fixture" }, { null })
            canonicalClient.execute("mutation { start }", null, null)
            assertEquals(1, saved.get())
        } finally {
            server.stop(0); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }

    @Test fun `retryable upload failures do not repeat the file mutation`(): Unit = runBlocking {
        val accepted = AtomicInteger()
        val http = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange -> exchange.use {
            assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("multipart/form-data"))
            assertTrue(exchange.requestBody.readAllBytes().decodeToString().contains("FILE-BYTES"))
            accepted.incrementAndGet()
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(503, -1)
        } }
        server.start()
        try {
            val client = BoscaGraphQLClient("http://127.0.0.1:${server.address.port}/graphql", http, { "bsk_fixture" }, { null })
            assertFailsWith<BoscaHttpException> {
                client.executeUpload("mutation Up(${'$'}file: Upload!) { upload(file: ${'$'}file) }",
                    buildJsonObject { put("file", JsonNull) }, "Up", listOf(bosca.graphql.client.GraphQLUpload("variables.file",
                        bosca.graphql.client.Upload("fixture.txt", "text/plain", "FILE-BYTES".toByteArray()))))
            }
            assertEquals(1, accepted.get())
        } finally {
            server.stop(0); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }

    @Test fun `retryable responses never repeat a GraphQL mutation`(): Unit = runBlocking {
        for (code in listOf(408, 503)) {
            val accepted = AtomicInteger()
            val http = OkHttpClient()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/graphql") { exchange -> exchange.use {
                val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                assertEquals("mutation { start }", request.getValue("query").jsonPrimitive.content)
                accepted.incrementAndGet()
                exchange.responseHeaders.add("Retry-After", "0")
                val bytes = "unavailable".toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } }
            server.start()
            try {
                val client = BoscaGraphQLClient("http://127.0.0.1:${server.address.port}/graphql", http, { "bsk_fixture" }, { null })
                assertEquals(code, assertFailsWith<BoscaHttpException> { client.execute("mutation { start }", null, null) }.statusCode)
                assertEquals(1, accepted.get(), "HTTP $code repeated an accepted operation")
            } finally {
                server.stop(0); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            }
        }
    }

    @Test fun `lost response after an accepted mutation is an IO failure without replay`(): Unit = runBlocking {
        val accepted = AtomicInteger()
        val http = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange -> exchange.use {
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            if (request.getValue("query").jsonPrimitive.content.startsWith("mutation")) {
                accepted.incrementAndGet()
                // The request has been accepted; close without acknowledging its outcome.
            } else {
                val bytes = """{"data":{"ready":true}}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            }
        } }
        server.start()
        try {
            val client = BoscaGraphQLClient("http://127.0.0.1:${server.address.port}/graphql", http, { "bsk_fixture" }, { null })
            client.execute("{ ready }", null, null)
            assertFailsWith<java.io.IOException> { client.execute("mutation { start }", null, null) }
            assertEquals(1, accepted.get())
        } finally {
            server.stop(0); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}
