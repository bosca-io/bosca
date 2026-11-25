package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.KitHarnessConfiguration
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class KitRuntimeClientTest {
    private val config = KitHarnessConfiguration("fixture", "http://runtime", "http://bosca/graphql", scopes = listOf("content:view"),
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64),
        versions = bosca.ai.kit.harness.RuntimeVersions("test", "test", "test", "codex-0.160.1"))

    @Test fun `control budget includes both response headers and response body`(): Unit = runBlocking {
        for (body in listOf(false, true)) withStalledServer(body, configuration = config.copy(connectionSeconds = 1)) { client, session, received ->
            withTimeout(5000) {
                assertFailsWith<java.net.http.HttpTimeoutException> { client.claim(session, "runtime-control-token") }
            }
            assertTrue(received.isCompleted)
            assertTrue(currentCoroutineContext().isActive, "A local request deadline must not cancel its caller")
        }
    }

    @Test fun `MCP readiness and restore use their own request budgets`(): Unit = runBlocking {
        withStalledServer(false, configuration = config.copy(connectionSeconds = 30, readinessSeconds = 1, restoreSeconds = 1)) { client, session, _ ->
            val archive = Files.createTempFile("kit-restore-timeout-", ".tar.gz")
            try {
                withTimeout(5000) {
                    assertFailsWith<java.net.http.HttpTimeoutException> { client.configureMcp(session, "runtime-control-token", emptyList()) }
                    assertFailsWith<java.net.http.HttpTimeoutException> { client.restore(session.copy(archiveSha256 = "fixture"), "runtime-control-token", archive) }
                }
            } finally { Files.deleteIfExists(archive) }
        }
    }

    @Test fun `archive export and download use the configured archive budget`(): Unit = runBlocking {
        withStalledServer(false, configuration = config.copy(connectionSeconds = 30, archiveSeconds = 1)) { client, session, _ ->
            val target = Files.createTempFile("kit-archive-timeout-", ".tar.gz")
            try {
                withTimeout(5000) {
                    assertFailsWith<java.net.http.HttpTimeoutException> { client.archive(session, "runtime-control-token") }
                    assertFailsWith<java.net.http.HttpTimeoutException> { client.download(session, "runtime-control-token", target, 1000) }
                }
            } finally { Files.deleteIfExists(target) }
        }
    }

    @Test fun `MCP preparation can outlast a shorter control budget`(): Unit = runBlocking {
        withStalledServer(false, configuration = config.copy(connectionSeconds = 1, readinessSeconds = 5), reply = "[]") { client, session, _ ->
            assertEquals(emptyList(), client.configureMcp(session, "runtime-control-token", emptyList()))
        }
    }

    @Test fun `cancelling a blocked control request interrupts the HTTP exchange`(): Unit = runBlocking {
        withStalledServer(false) { client, session, received ->
            val request = launch { client.claim(session, "runtime-control-token") }
            withTimeout(5000) { received.await() }
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        }
    }

    @Test fun `cancelling a stalled archive body interrupts the download`(): Unit = runBlocking {
        withStalledServer(true) { client, session, received ->
            val path = Files.createTempFile("kit-download-cancellation-", ".tar.gz")
            try {
                val request = launch { client.download(session, "runtime-control-token", path, 1000) }
                withTimeout(5000) { received.await() }
                withTimeout(5000) { while (Files.size(path) == 0L) delay(10) }
                withTimeout(2000) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
                assertTrue(Files.size(path) < 1000)
            } finally { Files.deleteIfExists(path) }
        }
    }

    @Test fun `truncated archive response remains an IO failure`(): Unit = runBlocking {
        withStalledServer(body = true, truncate = true) { client, session, _ ->
            val path = Files.createTempFile("kit-download-truncation-", ".tar.gz")
            try {
                assertFailsWith<java.io.IOException> {
                    withTimeout(5000) { client.download(session, "runtime-control-token", path, 1000) }
                }
            } finally { Files.deleteIfExists(path) }
        }
    }

    private suspend fun withStalledServer(body: Boolean, truncate: Boolean = false, configuration: KitHarnessConfiguration = config, reply: String? = null,
                                         action: suspend CoroutineScope.(KitRuntimeClient, KitRuntimeSession, CompletableDeferred<Unit>) -> Unit) = coroutineScope {
        val received = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                exchange.use {
                    if (body) {
                        exchange.sendResponseHeaders(200, 1000)
                        exchange.responseBody.write(byteArrayOf(1))
                        exchange.responseBody.flush()
                    }
                    received.complete(Unit)
                    if (reply != null) {
                        release.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        val bytes = reply.toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    } else if (!truncate) release.await()
                }
            }
            start()
        }
        try {
            val session = KitRuntimeSession(UUID.random(), UUID.random(), Json.encodeToJsonElement(KitHarnessConfiguration.serializer(), configuration), JsonObject(emptyMap()),
                endpoint = "http://127.0.0.1:${server.address.port}", idleAt = OffsetDateTime.now())
            action(KitRuntimeClient(Json), session, received)
        } finally {
            release.countDown()
            server.stop(0)
            executor.close()
        }
    }
}
