package bosca.cli.mcp

import bosca.cli.api.KitHarnessFixture
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class KitCancellationMcpTest {
    @Test
    fun `SDK request cancellation reaches only the matching ask and suppresses its response`() = runBlocking {
        KitHarnessFixture("CANCELLED", waitForCancellation = true).use { fixture ->
            val wire = Wire()
            val server = Server(Implementation("test", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(false))))
            KitToolRegistrar.registerAll(server, fixture.api())
            server.createSession(KitMcpTransport(wire, this))
            wire.receive(JSONRPCRequest(1, "initialize", buildJsonObject {
                put("protocolVersion", "2025-11-25"); putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "test"); put("version", "1") }
            }))
            wire.receive(JSONRPCNotification("notifications/initialized"))
            wire.receive(JSONRPCRequest(2, "tools/call", buildJsonObject {
                put("name", "kit_chat"); putJsonObject("arguments") { put("action", "ask"); put("message", "Work") }
            }))
            val activeRequests = coroutineContext.job.children.toList()
            withTimeout(5_000) { while ("SendKitChatMessage" !in fixture.operations) delay(5) }
            wire.receive(JSONRPCNotification("notifications/cancelled", buildJsonObject { put("requestId", 999) }))
            delay(20)
            assertTrue("CancelKitChatSession" !in fixture.operations)
            repeat(2) {
                wire.receive(JSONRPCNotification("notifications/cancelled", buildJsonObject { put("requestId", 2) }))
            }
            withTimeout(5_000) {
                activeRequests.forEach { it.join() }
            }
            assertEquals(1, fixture.operations.count { it == "CancelKitChatSession" })
            assertTrue(wire.sent.none { it is JSONRPCResponse && it.id == RequestId.NumberId(2) })
            wire.receive(JSONRPCNotification("notifications/cancelled", buildJsonObject { put("requestId", 2) }))
            assertEquals(1, fixture.operations.count { it == "CancelKitChatSession" })
        }
    }

    @Test
    fun `explicit cancel acknowledges intent without claiming remote completion`() = runBlocking {
        KitHarnessFixture(waitForCancellation = true).use { fixture ->
            val result = KitToolRegistrar.handleChat(fixture.api(), buildJsonObject {
                put("action", "cancel"); put("sessionId", KitHarnessFixture.SESSION_ID)
            })
            assertEquals(JsonPrimitive(true), result.structuredContent?.get("cancellationRequested"))
            assertContains((result.content.single() as TextContent).text, "Read the session history")
            assertEquals(listOf("GetKitChatSession", "CancelKitChatSession"), fixture.operations)
        }
    }

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
