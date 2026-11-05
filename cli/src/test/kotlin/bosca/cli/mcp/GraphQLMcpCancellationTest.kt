package bosca.cli.mcp

import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class GraphQLMcpCancellationTest {
    @Test fun `MCP cancellation interrupts GraphQL headers and bodies without blocking other requests`(): Unit = runBlocking {
        for (body in listOf(false, true)) for (toolName in listOf("bosca_graphql", "other_tool")) {
            val received = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val count = AtomicInteger()
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = executor
                createContext("/graphql") { exchange -> exchange.use {
                    assertEquals("Bearer bsk_graphql_fixture", exchange.requestHeaders.getFirst("Authorization"))
                    count.incrementAndGet()
                    val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                    if (request.getValue("query").jsonPrimitive.content.contains("Slow")) {
                        if (body) {
                            exchange.sendResponseHeaders(200, 1000)
                            exchange.responseBody.write(byteArrayOf(1)); exchange.responseBody.flush()
                        }
                        received.complete(Unit)
                        release.await()
                    } else {
                        val bytes = """{"data":{"echo":"ready"}}""".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } }
                start()
            }
            val network = NetworkClient("http://127.0.0.1:${http.address.port}/graphql").apply { tokenProvider = { "bsk_graphql_fixture" } }
            val requests = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            try {
                val wire = Wire()
                val server = Server(Implementation("test", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(false))))
                GraphQLToolRegistrar.registerAll(server, network.boscaGraphql)
                server.addTool(GraphQLToolRegistrar.tool.copy(name = "other_tool")) { request ->
                    GraphQLToolRegistrar.handle(network.boscaGraphql, request.arguments.orEmpty())
                }
                server.createSession(KitMcpTransport(wire, requests))
                withTimeout(5000) {
                    wire.receive(JSONRPCRequest(1, "initialize", buildJsonObject {
                        put("protocolVersion", "2025-11-25"); putJsonObject("capabilities") {}
                        putJsonObject("clientInfo") { put("name", "test"); put("version", "1") }
                    }))
                    wire.receive(JSONRPCNotification("notifications/initialized"))
                    wire.receive(call(2, "query Slow { echo }", toolName))
                    received.await()
                    wire.receive(call(3, "{ echo }"))
                    while (wire.sent.none { it is JSONRPCResponse && it.id == RequestId.NumberId(3) }) delay(5)
                    while (network.http.dispatcher.runningCallsCount() != 1) delay(5)
                    wire.receive(JSONRPCNotification("notifications/cancelled", buildJsonObject { put("requestId", 999) }))
                    delay(20)
                    assertEquals(1, network.http.dispatcher.runningCallsCount())
                    wire.receive(JSONRPCNotification("notifications/cancelled", buildJsonObject { put("requestId", 2) }))
                    while (network.http.dispatcher.runningCallsCount() != 0) delay(5)
                    wire.receive(call(4, "{ echo }"))
                    while (wire.sent.none { it is JSONRPCResponse && it.id == RequestId.NumberId(4) }) delay(5)
                    requests.coroutineContext.job.children.toList().forEach { it.join() }
                    assertFalse(wire.sent.any { it is JSONRPCResponse && it.id == RequestId.NumberId(2) })
                    assertFalse(wire.sent.any { it is JSONRPCError && it.id == RequestId.NumberId(2) })
                    assertEquals(3, count.get(), "The cancelled operation must not be retried")
                }
            } finally {
                requests.cancel(); release.countDown(); http.stop(0); executor.close()
                network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll()
            }
        }
    }

    private fun call(id: Int, query: String, toolName: String = "bosca_graphql") = JSONRPCRequest(id.toLong(), "tools/call", buildJsonObject {
        put("name", toolName); putJsonObject("arguments") { put("action", "execute"); put("query", query) }
    })

    private class Wire : Transport {
        val sent = CopyOnWriteArrayList<JSONRPCMessage>()
        lateinit var receive: suspend (JSONRPCMessage) -> Unit
        override suspend fun start() {}
        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) { sent += message }
        override suspend fun close() {}
        override fun onClose(block: () -> Unit) {}
        override fun onError(block: (Throwable) -> Unit) {}
        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) { receive = block }
    }
}
