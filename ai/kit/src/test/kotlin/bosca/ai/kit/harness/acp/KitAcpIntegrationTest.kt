package bosca.ai.kit.harness.acp

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.JsonRpcException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KitAcpIntegrationTest {
    private var selectedPermission = "allow"
    private val permissions = mutableListOf<SessionUpdate.ToolCallUpdate>()
    private val notifications = mutableListOf<SessionUpdate>()
    private val operations = ClientOperationsFactory { _, _ ->
        object : ClientSessionOperations {
            override suspend fun requestPermissions(
                toolCall: SessionUpdate.ToolCallUpdate,
                permissions: List<PermissionOption>,
                _meta: JsonElement?,
            ): RequestPermissionResponse {
                this@KitAcpIntegrationTest.permissions.add(toolCall)
                return RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId(selectedPermission)))
            }

            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
                notifications.add(notification)
            }
        }
    }
    private val parameters = SessionCreationParameters("/workspace", emptyList())

    @Test
    fun `real Koog strategy streams and answers permission over authenticated websocket without local inference`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness().use { harness ->
                harness.mode = "permission"
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        val selection = connection.openSession(parameters, operations)
                        assertEquals(AcpSessionContinuity.NEW, selection.continuity)
                        val events = mutableListOf<Event>()
                        val executor = mockk<PromptExecutor>()
                        val response = runTurn(KitAcpTurn(connection, selection.session, listOf(ContentBlock.Text("Read document")),
                            buildJsonObject { put("turnId", "turn-1"); put("generation", 3) }, events::add), executor)
                        assertEquals(StopReason.END_TURN, response.stopReason)
                        assertEquals(listOf("before ", "after approval"), text(events))
                        assertIs<Event.PromptResponseEvent>(events.last())
                        assertEquals("tool-1", permissions.single().toolCallId.value)
                        assertEquals("allow", harness.permissions.single().getValue("result").jsonObject
                            .getValue("outcome").jsonObject.getValue("optionId").jsonPrimitive.content)
                        val initialization = harness.requests.first().getValue("params").jsonObject
                        assertFalse(initialization.getValue("clientCapabilities").jsonObject["terminal"]?.jsonPrimitive?.content == "true")
                        assertEquals("turn-1", harness.requests.last().getValue("params").jsonObject
                            .getValue("_meta").jsonObject.getValue("turnId").jsonPrimitive.content)
                        confirmVerified(executor)
                    }
                }
            }
        }
    }

    @Test
    fun `cancellation drains updates and returns the confirmed terminal result`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness().use { harness ->
                harness.mode = "wait"
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        val session = connection.openSession(parameters, operations).session
                        val events = mutableListOf<Event>()
                        val result = async { runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Text("Wait")), JsonObject(emptyMap()), events::add)) }
                        harness.promptStarted.await()
                        session.cancel()
                        assertEquals(StopReason.CANCELLED, result.await().stopReason)
                        assertEquals(listOf("before ", "drained"), text(events))
                        assertEquals(1, harness.requests.count { it["method"]?.jsonPrimitive?.content == "session/cancel" })
                    }
                }
            }
        }
    }

    @Test
    fun `disconnect never resubmits prompt and reconnect restores advertised opaque session`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness(resume = true).use { harness ->
                harness.mode = "disconnect"
                httpClient().use { http ->
                    val id = connect(harness, http).use { connection ->
                        val session = connection.openSession(parameters, operations).session
                        assertFailsWith<AcpConnectionException> {
                            runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Text("Side effect")), JsonObject(emptyMap()), {}))
                        }
                        session.sessionId.value
                    }
                    harness.mode = "complete"
                    connect(harness, http).use { connection ->
                        val restored = connection.openSession(parameters, operations, id)
                        assertEquals(AcpSessionContinuity.RESUMED, restored.continuity)
                        assertEquals(id, restored.session.sessionId.value)
                        assertEquals(1, harness.requests.count { it["method"]?.jsonPrimitive?.content == "session/prompt" })
                        assertEquals(1, harness.newSessions.get())
                    }
                }
            }
        }
    }

    @Test
    fun `restore uses load only when advertised and otherwise reports reconstruction`(): Unit = runBlocking {
        withTimeout(15_000) {
            for (load in listOf(true, false)) {
                SyntheticAcpHarness(load = load).use { harness ->
                    httpClient().use { http ->
                        connect(harness, http).use { connection ->
                            val restored = connection.openSession(parameters, operations, "opaque:previous")
                            assertEquals(if (load) AcpSessionContinuity.LOADED else AcpSessionContinuity.RECONSTRUCTED, restored.continuity)
                            assertEquals(if (load) "session/load" else "session/new", harness.requests.last()["method"]?.jsonPrimitive?.content)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `unsupported ACP version fails before session creation`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness(protocolVersion = 2).use { harness ->
                httpClient().use { http ->
                    assertFailsWith<IllegalStateException> { connect(harness, http) }
                    assertEquals(0, harness.newSessions.get())
                }
            }
        }
    }

    @Test
    fun `denied permission is returned to the harness`(): Unit = runBlocking {
        withTimeout(15_000) {
            selectedPermission = "deny"
            SyntheticAcpHarness().use { harness ->
                harness.mode = "permission"
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        val session = connection.openSession(parameters, operations).session
                        runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Text("Read")), JsonObject(emptyMap()), {}))
                        assertEquals("deny", harness.permissions.single().getValue("result").jsonObject
                            .getValue("outcome").jsonObject.getValue("optionId").jsonPrimitive.content)
                    }
                }
            }
        }
    }

    @Test
    fun `failed native restore is visible without creating replacement session`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness(load = true, resume = true, failRestore = true).use { harness ->
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        val failure = assertFailsWith<JsonRpcException> {
                            connection.openSession(parameters, operations, "opaque:prior")
                        }
                        assertEquals("Checkpoint unavailable", failure.message)
                        assertEquals(0, harness.newSessions.get())
                        assertEquals("session/resume", harness.requests.last()["method"]?.jsonPrimitive?.content)
                    }
                }
            }
        }
    }

    @Test
    fun `unsupported input and MCP transports fail before remote execution`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness().use { harness ->
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        assertFailsWith<IllegalArgumentException> {
                            connection.openSession(SessionCreationParameters("/workspace", listOf(
                                McpServer.Http("https://approved.test/mcp", "approved", emptyList()),
                            )), operations)
                        }
                        assertEquals(0, harness.newSessions.get())
                        val session = connection.openSession(parameters, operations).session
                        assertFailsWith<IllegalArgumentException> {
                            runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Image("aW1hZ2U=", "image/png")), JsonObject(emptyMap()), {}))
                        }
                        assertTrue(harness.requests.none { it["method"]?.jsonPrimitive?.content == "session/prompt" })
                    }
                }
            }
        }
    }

    @Test
    fun `invalid runtime bearer token is rejected before ACP initialization`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness().use { harness ->
                httpClient().use { http ->
                    val factory = AcpWebSocketTransportFactory(http, harness.endpoint()) { "invalid" }
                    assertFailsWith<Exception> { KitAcpConnection.connect(this, factory) }
                    assertTrue(harness.requests.isEmpty())
                }
            }
        }
    }

    @Test
    fun `malformed and binary frames produce transport failure without retry`(): Unit = runBlocking {
        withTimeout(15_000) {
            for (mode in listOf("malformed", "binary")) {
                SyntheticAcpHarness().use { harness ->
                    harness.mode = mode
                    httpClient().use { http ->
                        connect(harness, http).use { connection ->
                            val session = connection.openSession(parameters, operations).session
                            assertFailsWith<AcpConnectionException> {
                                runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Text("Work")), JsonObject(emptyMap()), {}))
                            }
                            assertEquals(1, harness.requests.count { it["method"]?.jsonPrimitive?.content == "session/prompt" })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `parent cancellation is preserved separately from remote disconnect`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness().use { harness ->
                harness.mode = "wait"
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        val session = connection.openSession(parameters, operations).session
                        val running = async {
                            runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Text("Work")), JsonObject(emptyMap()), {}))
                        }
                        harness.promptStarted.await()
                        running.cancel()
                        assertFailsWith<CancellationException> { running.await() }
                    }
                }
            }
        }
    }

    @Test
    fun `workspace and selected MCP connections are reapplied on new load and resume`(): Unit = runBlocking {
        withTimeout(15_000) {
            for ((load, resume) in listOf(false to false, true to false, true to true)) {
                SyntheticAcpHarness(load = load, resume = resume, httpMcp = true).use { harness ->
                    httpClient().use { http ->
                        connect(harness, http).use { connection ->
                            val selected = SessionCreationParameters("/workspace/current", listOf(
                                McpServer.Http(url = "https://approved.test/mcp", name = "selected", headers = emptyList()),
                            ))
                            connection.openSession(selected, operations, if (load) "opaque:previous" else null)
                            val params = harness.requests.last().getValue("params").jsonObject
                            assertEquals("/workspace/current", params.getValue("cwd").jsonPrimitive.content)
                            val server = params.getValue("mcpServers").jsonArray.single().jsonObject
                            assertEquals("http", server.getValue("type").jsonPrimitive.content)
                            assertEquals("https://approved.test/mcp", server.getValue("url").jsonPrimitive.content)
                            assertEquals("selected", server.getValue("name").jsonPrimitive.content)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `provider authentication uses only the configured advertised method`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness(authMethods = listOf("api-key")).use { harness ->
                httpClient().use { http ->
                    val factory = AcpWebSocketTransportFactory(http, harness.endpoint()) { "test-runtime-token" }
                    KitAcpConnection.connect(this, factory, authenticationMethod = "api-key").use { connection ->
                        connection.openSession(parameters, operations)
                        val auth = harness.requests.single { it["method"] == kotlinx.serialization.json.JsonPrimitive("authenticate") }
                        assertEquals("api-key", auth.getValue("params").jsonObject.getValue("methodId").jsonPrimitive.content)
                        assertFalse(auth.getValue("params").jsonObject.containsKey("_meta"))
                    }
                    assertFailsWith<IllegalArgumentException> {
                        KitAcpConnection.connect(this, factory, authenticationMethod = "unadvertised")
                    }
                    assertEquals(1, harness.requests.count { it["method"]?.jsonPrimitive?.content == "authenticate" })
                }
            }
        }
    }

    @Test
    fun `remote harness cannot invoke Runner host callbacks`(): Unit = runBlocking {
        withTimeout(15_000) {
            SyntheticAcpHarness().use { harness ->
                harness.mode = "host"
                httpClient().use { http ->
                    connect(harness, http).use { connection ->
                        val session = connection.openSession(parameters, operations).session
                        for (method in listOf(
                            "fs/read_text_file", "fs/write_text_file", "terminal/create", "terminal/output",
                            "terminal/release", "terminal/wait_for_exit", "terminal/kill", "elicitation/create",
                        )) {
                            harness.hostMethod = method
                            runTurn(KitAcpTurn(connection, session, listOf(ContentBlock.Text("Work")), JsonObject(emptyMap()), {}))
                            assertEquals("-32601", harness.permissions.last().getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                        }
                    }
                }
            }
        }
    }

    private fun httpClient() = HttpClient(CIO) { install(WebSockets) }

    private suspend fun CoroutineScope.connect(harness: SyntheticAcpHarness, http: HttpClient) =
        KitAcpConnection.connect(this, AcpWebSocketTransportFactory(http, harness.endpoint()) { "test-runtime-token" })

    private suspend fun runTurn(turn: KitAcpTurn, executor: PromptExecutor = mockk()) = AIAgentService(
        promptExecutor = executor,
        agentConfig = AIAgentConfig(
            prompt = prompt("acp-integration") {},
            model = LLModel(OpenAILLMProvider, "unused", listOf(LLMCapability.Completion)),
            maxAgentIterations = 3,
        ),
        strategy = kitAcpStrategy(),
    ).createAgentAndRun(turn)

    private fun text(events: List<Event>) = events.filterIsInstance<Event.SessionUpdateEvent>().map {
        assertIs<ContentBlock.Text>(assertIs<SessionUpdate.AgentMessageChunk>(it.update).content).text
    }
}
