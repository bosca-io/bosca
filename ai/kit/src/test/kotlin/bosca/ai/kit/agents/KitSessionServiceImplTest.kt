package bosca.ai.kit.agents

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.prompt.message.Message
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.chat.model.AnalyticsInvestigationStep
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.routing.RouteResponse
import bosca.ai.kit.agents.session.InMemoryKitSessionService
import bosca.ai.kit.agents.session.KitSessionCheckpoint
import bosca.ai.kit.agents.session.KitSessionRepository
import bosca.ai.kit.agents.session.KitSessionServiceImpl
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.image.ImageServices
import bosca.graphql.GraphQLService
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.KitToolContext
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the DURABLE [KitSessionServiceImpl] splits a checkpoint correctly: the (large)
 * `AgentCheckpointData` bytes go to **object storage**, and a small **index row** goes to
 * [KitSessionRepository] (`kit.checkpoint`) — never the blob in a DB column — so a run's checkpoints
 * can be listed and the latest found. The pair round-trips back to the same checkpoint. A real
 * checkpoint is captured from an actual Kit run (its constructor is internal, so it can't be hand-built)
 * and then fed through the durable impl. It also records the user's turn and Kit's reply to chat history
 * as real Koog [Message]s, and proves an analytics turn rides a SECOND visualization row that
 * [KitSessionServiceImpl.getMessages] skips — so charts survive without breaking conversation replay.
 */
class KitSessionServiceImplTest {

    private val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Tools, LLMCapability.Completion))
    private val models = KitModels(model)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Kit's own Json (the platform Json + Koog's reflective polymorphic types), as DI builds it.
    private val kitJson = KitJson(koogJson(json))
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `saveCheckpoint writes the blob to object storage, indexes it, and round-trips`() = runTest {
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

        // A real checkpoint, captured from an actual CLARIFY run (InMemory holds the live object).
        val captured = InMemoryKitSessionService()
        val routingJson = json.encodeToString(RouteResponse(route = KitRoute.CLARIFY, question = "Which did you mean?"))
        val kit = KitAgent(
            mockk<BibleService>(relaxed = true), mockk<MetadataService>(relaxed = true),
            mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), mockk<SqlQuery>(relaxed = true), mockk<ScriptServices>(relaxed = true), mockk<ImageServices>(relaxed = true), mockk<GraphQLService>(relaxed = true),
            getMockExecutor { mockLLMAnswer(routingJson) onRequestContains "Decide how to handle" },
            models, kitJson, captured, mockk(relaxed = true),
        ).agent
        withContext(KitToolContext(auth)) { kit.run(kitRequest("do the thing"), sessionId.toString()) }
        val checkpoint = assertNotNull(captured.getLatestCheckpoint(sessionId), "the run should have produced a checkpoint")

        // Object storage: a backing map keyed by the StringObjectPath's string form.
        val blobs = mutableMapOf<String, ByteArray>()
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        coEvery { objectStorage.setInputStream(any(), any(), any()) } answers {
            val bytes = secondArg<InputStream>().readBytes()
            blobs[firstArg<ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
        coEvery { objectStorage.getInputStream(any()) } answers {
            (blobs[firstArg<ObjectPath>().toString()] ?: error("no object at ${firstArg<ObjectPath>()}")).inputStream()
        }

        // Checkpoint index: a small in-memory stand-in for the kit.checkpoint table.
        val rows = mutableListOf<KitSessionCheckpoint>()
        val repository = mockk<KitSessionRepository>(relaxed = true)
        coEvery { repository.add(capture(rows)) } answers { }
        coEvery { repository.latestBySession(any()) } answers {
            val id = firstArg<Uuid>(); rows.filter { it.sessionId == id }.maxByOrNull { it.version }
        }

        val durable = KitSessionServiceImpl(mockk(relaxed = true), repository, objectStorage, kitJson)
        durable.saveCheckpoint(sessionId, checkpoint)

        // The blob landed in object storage under the run/checkpoint/version key…
        val checkpointId = Uuid.parse(checkpoint.checkpointId)
        val key = "kit/sessions/$sessionId/$checkpointId/${checkpoint.version}.json"
        assertTrue(blobs.containsKey(key), "checkpoint blob should be written to object storage at $key, got: ${blobs.keys}")
        // …and a small index row was written. Saved outside any sub-agent KitSessionContext, so there is
        // no owning chat session above it: parentSessionId is null (this run IS the top of the tree).
        val row = rows.single()
        assertNull(row.parentSessionId)
        assertEquals(sessionId, row.sessionId)
        assertEquals(checkpointId, row.checkpointId)

        // The pair round-trips back to the same checkpoint.
        val restored = assertNotNull(durable.getLatestCheckpoint(sessionId), "the checkpoint should be readable back")
        assertEquals(checkpoint.checkpointId, restored.checkpointId)
        assertEquals(checkpoint.version, restored.version)
    }

    @Test
    fun `clearSession deletes every run's blob for the chat session, then the index rows`() = runTest {
        val parentSessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val subRunId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val cpParent = Uuid.parse("22222222-2222-4222-8222-222222222222")
        val cpRoute = Uuid.parse("33333333-3333-4333-8333-333333333333")
        // A session's checkpoints span its parent run (id == the chat session) and a sub-agent run (its UUID).
        val rows = listOf(
            KitSessionCheckpoint(parentSessionId = parentSessionId, sessionId = parentSessionId, checkpointId = cpParent, version = 1),
            KitSessionCheckpoint(parentSessionId = parentSessionId, sessionId = subRunId, checkpointId = cpRoute, version = 0),
        )
        val deleted = mutableListOf<ObjectPath>()
        val objectStorage = mockk<ObjectStorageService>(relaxed = true)
        coEvery { objectStorage.delete(capture(deleted)) } answers { }
        val repository = mockk<KitSessionRepository>(relaxed = true)
        coEvery { repository.byParentSession(parentSessionId) } returns rows

        KitSessionServiceImpl(mockk(relaxed = true), repository, objectStorage, kitJson).clearSession(parentSessionId)

        // Each run's blob (keyed by its own session id / checkpoint / version) is dropped…
        assertEquals(
            setOf("kit/sessions/$parentSessionId/$cpParent/1.json", "kit/sessions/$subRunId/$cpRoute/0.json"),
            deleted.map { it.toString() }.toSet(),
        )
        // …and the index rows for the whole chat session are removed.
        coVerify { repository.deleteByParentSession(parentSessionId) }
    }

    @Test
    fun `recordUserMessage stores a Koog User message that getMessages round-trips`() = runTest {
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val store = mutableListOf<ChatHistoryMessage>()
        val chatHistory = mockk<ChatHistoryService>(relaxed = true)
        coEvery { chatHistory.addMessage(any()) } answers { store.add(firstArg()); firstArg() }
        coEvery { chatHistory.getMessages(sessionId) } answers { store.toList() }

        val durable = KitSessionServiceImpl(chatHistory, mockk(relaxed = true), mockk(relaxed = true), kitJson)
        durable.recordUserMessage(
            sessionId,
            ChatMessageInput(role = "user", parts = listOf(ChatMessagePartInput(type = "text", text = "Hi Kit"))),
        )

        // The user's turn is ONE conversational row authored by the Koog User role…
        val row = store.single()
        assertEquals(sessionId, row.sessionId)
        assertEquals("User", row.author)
        // …and getMessages decodes it straight back into a Message.User carrying the text.
        val message = durable.getMessages(sessionId).single()
        assertEquals("Hi Kit", assertIs<Message.User>(message).textContent())
    }

    @Test
    fun `recordResponse stores a Koog Assistant message that getMessages round-trips`() = runTest {
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val store = mutableListOf<ChatHistoryMessage>()
        val chatHistory = mockk<ChatHistoryService>(relaxed = true)
        coEvery { chatHistory.addMessage(any()) } answers { store.add(firstArg()); firstArg() }
        coEvery { chatHistory.getMessages(sessionId) } answers { store.toList() }

        val durable = KitSessionServiceImpl(chatHistory, mockk(relaxed = true), mockk(relaxed = true), kitJson)
        durable.recordResponse(sessionId, KitResponse.Text("Hello from Kit"))

        val row = store.single()
        assertEquals(sessionId, row.sessionId)
        assertEquals("Assistant", row.author)
        val message = durable.getMessages(sessionId).single()
        assertEquals("Hello from Kit", assertIs<Message.Assistant>(message).textContent())
    }

    @Test
    fun `recordResponse records an Analytics turn as a summary message plus a tool-display row getMessages skips`() = runTest {
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val store = mutableListOf<ChatHistoryMessage>()
        val chatHistory = mockk<ChatHistoryService>(relaxed = true)
        coEvery { chatHistory.addMessage(any()) } answers { store.add(firstArg()); firstArg() }
        coEvery { chatHistory.getMessages(sessionId) } answers { store.toList() }

        val durable = KitSessionServiceImpl(chatHistory, mockk(relaxed = true), mockk(relaxed = true), kitJson)
        durable.recordResponse(
            sessionId,
            KitResponse.Analytics(
                summary = "There are 42 total users.",
                query = "SELECT status, count(*) AS total FROM users GROUP BY status",
                columns = listOf("status", "total"),
                rows = listOf(listOf("active", "42")),
                visualization = "bar",
                savedQueryId = "query-id",
                savedQueryKey = "users-by-status",
                visualizationId = "visualization-id",
                dashboardId = "dashboard-id",
                investigation = listOf(
                    AnalyticsInvestigationStep(
                        sequence = 1,
                        kind = AnalyticsInvestigationKind.QUERY,
                        tool = "execute_query",
                        sql = "SELECT status, count(*) AS total FROM users GROUP BY status",
                        resultSummary = "1 rows in 8 ms",
                        startedAt = "2026-07-21T12:00:00Z",
                    ),
                ),
            ),
        )

        // TWO rows are written — the conversational summary, then the visualization — proving we DON'T
        // lose the chart: it rides its own row rather than being dropped or crammed into a Message.
        assertEquals(2, store.size, "an analytics answer should record a summary turn + a visualization turn")

        // 1) The summary rides the conversational lane (a Koog Assistant message), and is the ONLY thing
        //    getMessages returns — the visualization row is NOT a Message, so it is skipped on replay.
        assertEquals("Assistant", store[0].author)
        val conversation = durable.getMessages(sessionId)
        assertEquals("There are 42 total users.", assertIs<Message.Assistant>(conversation.single()).textContent())

        // 2) The data rides the display lane as a ToolDisplayEvent the chat renders inline — reusing the
        //    platform viz vocabulary. Authored by AUTHOR (a non-role), which is how getMessages skips it.
        val vizRow = store[1]
        assertEquals("kit", vizRow.author)
        val viz = vizRow.event.jsonObject
        assertEquals("tool-display", viz["type"]!!.jsonPrimitive.content)
        assertEquals("BAR", viz["visualizationType"]!!.jsonPrimitive.content)
        val row = viz["data"]!!.jsonArray.single().jsonObject
        assertEquals("active", row["status"]!!.jsonPrimitive.content)
        assertEquals("42", row["total"]!!.jsonPrimitive.content)
        // The executed SQL rides the display row too, so the UI can show the answer's source.
        assertEquals("SELECT status, count(*) AS total FROM users GROUP BY status", viz["sourceQuery"]!!.jsonPrimitive.content)
        assertEquals("query-id", viz["savedQueryId"]!!.jsonPrimitive.content)
        assertEquals("users-by-status", viz["savedQueryKey"]!!.jsonPrimitive.content)
        assertEquals("visualization-id", viz["visualizationId"]!!.jsonPrimitive.content)
        assertEquals("dashboard-id", viz["dashboardId"]!!.jsonPrimitive.content)
        val investigation = viz["investigation"]!!.jsonArray.single().jsonObject
        assertEquals("QUERY", investigation["kind"]!!.jsonPrimitive.content)
        assertEquals("execute_query", investigation["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun `recordResponse configures a number visualization from its first result column`() = runTest {
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val store = mutableListOf<ChatHistoryMessage>()
        val chatHistory = mockk<ChatHistoryService>(relaxed = true)
        coEvery { chatHistory.addMessage(any()) } answers { store.add(firstArg()); firstArg() }

        val durable = KitSessionServiceImpl(chatHistory, mockk(relaxed = true), mockk(relaxed = true), kitJson)
        durable.recordResponse(
            sessionId,
            KitResponse.Analytics(
                summary = "The DAU count is 6 for today.",
                query = "SELECT COUNT(DISTINCT user_id) AS dau FROM events",
                columns = listOf("dau"),
                rows = listOf(listOf("6")),
                visualization = "number",
            ),
        )

        val visualization = store[1].event.jsonObject
        assertEquals("NUMBER", visualization["visualizationType"]!!.jsonPrimitive.content)
        assertEquals("dau", visualization["configuration"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("6", visualization["data"]!!.jsonArray.single().jsonObject["dau"]!!.jsonPrimitive.content)
    }
}
