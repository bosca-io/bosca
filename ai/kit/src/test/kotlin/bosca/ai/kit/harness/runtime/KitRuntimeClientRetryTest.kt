package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.RuntimeVersions
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.test.*

/** Real HTTP failures exercise retry safety, fencing, cancellation and the shared request budget. */
class KitRuntimeClientRetryTest {
    private val config = KitHarnessConfiguration("fixture", "http://runtime", "http://bosca/graphql", scopes = listOf("content:view"),
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64),
        versions = RuntimeVersions("test", "test", "test", "codex-0.160.1"))
    private val token = "private-control-credential"
    private val status = """{"sessionId":"fixture","generation":7,"phase":"READY","instanceId":"fixture-instance","epoch":11}"""

    @Test fun `replacement process identity fails once before accepting an operation result`(): Unit = runBlocking {
        for (code in listOf(200, 409)) withServer({ exchange, _ ->
            assertEquals("original-instance", exchange.requestHeaders.getFirst("X-Bosca-Instance"))
            exchange.responseHeaders.add("X-Bosca-Instance", "replacement-instance")
            reply(exchange, code, status)
        }) { client, session, count ->
            val failure = assertFailsWith<KitRuntimeReplacedException> { client.claim(session.copy(runtimeInstanceId = "original-instance"), token) }
            assertEquals(session.generation, failure.generation)
            assertEquals(1, count.get(), "An instance mismatch must not retry against the replacement")
        }
    }

    @Test fun `a fenced claim retries temporary responses with the same identity and bounded backoff`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<List<String?>>()
        val times = CopyOnWriteArrayList<Long>()
        withServer({ exchange, attempt ->
            times += System.nanoTime()
            requests += listOf(exchange.requestMethod, exchange.requestURI.toString(), exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("X-Bosca-Generation"), exchange.requestHeaders.getFirst("X-Bosca-Epoch"))
            reply(exchange, if (attempt < 3) 501 + attempt else 200, if (attempt < 3) "private failure body" else status)
        }) { client, session, count ->
            assertEquals("READY", client.claim(session, token).phase)
            assertEquals(3, count.get())
            assertEquals(List(3) { listOf("POST", "/v1/claim", "Bearer $token", "7", "11") }, requests.toList())
            assertTrue(times[1] - times[0] >= 100_000_000)
            assertTrue(times[2] - times[1] >= 200_000_000)
        }
    }

    @Test fun `status and durable receipt reads recover from temporary unavailability`(): Unit = runBlocking {
        for (receipt in listOf(false, true)) withServer({ exchange, attempt ->
            reply(exchange, if (attempt == 1) 504 else 200,
                if (attempt == 1) "unavailable" else if (receipt) """{"complete":true,"messages":[]}""" else status)
        }) { client, session, count ->
            if (receipt) assertTrue(assertNotNull(client.turn(session, token, "durable-turn")).complete)
            else assertEquals("READY", client.status(session, token).phase)
            assertEquals(2, count.get())
        }
    }

    @Test fun `temporary responses exhaust after three attempts and retain the HTTP failure`(): Unit = runBlocking {
        withServer({ exchange, _ -> reply(exchange, 503, "private infrastructure details") }) { client, session, count ->
            val failure = assertFailsWith<IllegalStateException> { client.claim(session, token) }
            assertContains(failure.message.orEmpty(), "HTTP 503")
            assertFalse(failure.message.orEmpty().contains("private"))
            assertEquals(3, count.get())
        }
    }

    @Test fun `authorization stale ownership missing data and other HTTP failures return immediately`(): Unit = runBlocking {
        for (code in listOf(400, 401, 403, 404, 408, 409, 429, 500)) withServer({ exchange, _ -> reply(exchange, code, "private details") }) { client, session, count ->
            val failure = assertFailsWith<IllegalStateException> { client.claim(session, token) }
            assertContains(failure.message.orEmpty(), "HTTP $code")
            assertEquals(1, count.get())
        }
        withServer({ exchange, _ -> reply(exchange, 404, "not found") }) { client, session, count ->
            assertNull(client.turn(session, token, "unknown-turn"))
            assertEquals(1, count.get())
        }
    }

    @Test fun `malformed successful responses are protocol failures without retries`(): Unit = runBlocking {
        withServer({ exchange, _ -> reply(exchange, 200, "{}") }) { client, session, count ->
            assertFailsWith<SerializationException> { client.claim(session, token) }
            assertEquals(1, count.get())
        }
    }

    @Test fun `lifecycle mutations and permission decisions are not automatically repeated`(): Unit = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange, _ ->
            paths += exchange.requestURI.path
            reply(exchange, 503, "unavailable")
        }) { client, session, count ->
            val file = Files.createTempFile("kit-retry-restore-", ".tar.gz")
            try {
                assertFailsWith<IllegalStateException> { client.start(session, token) }
                assertFailsWith<IllegalStateException> { client.archive(session, token) }
                assertFailsWith<IllegalStateException> { client.configureMcp(session, token, emptyList()) }
                assertFailsWith<IllegalStateException> { client.cancel(session, token, "durable-turn") }
                assertFailsWith<IllegalStateException> { client.respond(session, token, JsonObject(emptyMap())) }
                assertFailsWith<IllegalStateException> { client.restore(session.copy(archiveSha256 = "fixture"), token, file) }
                assertEquals(listOf("/v1/start", "/v1/archive", "/v1/mcp", "/v1/cancel", "/v1/permission", "/v1/restore"), paths.toList())
                assertEquals(6, count.get())
            } finally { Files.deleteIfExists(file) }
        }
    }

    @Test fun `a truncated read is retried and reconciles the original receipt`(): Unit = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        withServer({ exchange, attempt ->
            paths += exchange.requestURI.path
            if (attempt < 3) truncate(exchange) else reply(exchange, 200, """{"complete":true,"messages":[]}""")
        }) { client, session, count ->
            assertTrue(assertNotNull(client.turn(session, token, "original-turn")).complete)
            assertEquals(List(3) { "/v1/turns/original-turn" }, paths.toList())
            assertEquals(3, count.get())
        }
    }

    @Test fun `exhausted broken reads propagate the IO failure`(): Unit = runBlocking {
        withServer({ exchange, _ -> truncate(exchange) }) { client, session, count ->
            assertFailsWith<java.io.IOException> { client.status(session, token) }
            assertEquals(3, count.get())
        }
    }

    @Test fun `a lost lifecycle response is left for durable reconciliation`(): Unit = runBlocking {
        withServer({ exchange, _ -> truncate(exchange) }) { client, session, count ->
            assertFailsWith<java.io.IOException> { client.start(session, token) }
            assertEquals(1, count.get())
        }
    }

    @Test fun `TLS failures propagate without repeating the connection`(): Unit = runBlocking {
        val count = AtomicInteger()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use {
                    count.incrementAndGet()
                    socket.soTimeout = 2000
                    // A plaintext reply to ClientHello deterministically fails the TLS handshake.
                    socket.getInputStream().readNBytes(5)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
        }
        try {
            val session = KitRuntimeSession(UUID.random(), UUID.random(), Json.encodeToJsonElement(KitHarnessConfiguration.serializer(), config), JsonObject(emptyMap()),
                endpoint = "https://127.0.0.1:${server.localPort}", idleAt = OffsetDateTime.now())
            withTimeout(5000) { assertFailsWith<SSLException> { KitRuntimeClient(Json).claim(session, token) } }
            assertEquals(1, count.get())
        } finally {
            server.close()
            executor.shutdownNow()
            executor.close()
        }
    }

    @Test fun `request deadline includes every attempt and backoff`(): Unit = runBlocking {
        withServer({ exchange, _ ->
            Thread.sleep(400)
            reply(exchange, 503, "unavailable")
        }, config.copy(connectionSeconds = 1)) { client, session, count ->
            withTimeout(2500) {
                assertFailsWith<HttpTimeoutException> { client.claim(session, token) }
            }
            assertEquals(2, count.get())
            assertTrue(currentCoroutineContext().isActive)
        }
    }

    @Test fun `parent cancellation stops backoff without another request`(): Unit = runBlocking {
        val replied = CompletableDeferred<Unit>()
        withServer({ exchange, _ ->
            reply(exchange, 503, "unavailable")
            replied.complete(Unit)
        }) { client, session, count ->
            val request = launch { client.claim(session, token) }
            withTimeout(5000) { replied.await() }
            withTimeout(2000) { request.cancelAndJoin() }
            delay(450)
            assertTrue(request.isCancelled)
            assertEquals(1, count.get())
        }
    }

    private suspend fun withServer(handler: (HttpExchange, Int) -> Unit, configuration: KitHarnessConfiguration = config,
                                   action: suspend CoroutineScope.(KitRuntimeClient, KitRuntimeSession, AtomicInteger) -> Unit) = coroutineScope {
        val count = AtomicInteger()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange -> exchange.use { handler(exchange, count.incrementAndGet()) } }
            start()
        }
        try {
            val session = KitRuntimeSession(UUID.random(), UUID.random(), Json.encodeToJsonElement(KitHarnessConfiguration.serializer(), configuration), JsonObject(emptyMap()),
                generation = 7, epoch = 11, endpoint = "http://127.0.0.1:${server.address.port}", idleAt = OffsetDateTime.now())
            action(KitRuntimeClient(Json), session, count)
        } finally {
            server.stop(0)
            executor.close()
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        exchange.requestBody.readAllBytes()
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun truncate(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, 1000)
        exchange.responseBody.write(byteArrayOf(1))
        exchange.responseBody.flush()
    }
}
