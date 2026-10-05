package bosca.ai.kit.agents

import ai.koog.agents.snapshot.feature.isTombstone
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.ai.kit.agents.routing.RouteResponse
import bosca.ai.kit.agents.session.InMemoryKitSessionService
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Session-persistence wiring guard. The Bosca-backed persistence stack — `@Serializable` [KitState],
 * the platform `Json` as the agent config serializer, and the Koog `Persistence` feature installed
 * with [bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider] over a [InMemoryKitSessionService]
 * — must not break a normal Kit run when wired in (run with `agent.run(request, sessionId)`), and the
 * checkpoint Koog takes must round-trip back through the provider as valid [AgentCheckpointData].
 *
 * Note this does NOT assert "a finished run resumes without LLM calls": Koog's `Persistence` is
 * crash-resume for *interrupted* runs, not memoization — re-invoking a completed run on the same id
 * replays it. The real, stable guarantee is that the run succeeds and the session is checkpointed.
 */
class KitSessionTest {

    private val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Tools, LLMCapability.Completion))
    private val models = KitModels(model)
    private val auth = mockk<AuthenticationContext>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Kit's own Json (the platform Json + Koog's reflective polymorphic types), as DI builds it.
    private val kitJson = KitJson(koogJson(json))

    @Test
    fun `a Kit run with Persistence installed completes, checkpoints, and records its reply to history`() = runTest {
        val sessions = InMemoryKitSessionService()
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val question = "Did you want a document, or an answer about your content?"
        val routingJson = json.encodeToString(RouteResponse(route = KitRoute.CLARIFY, question = question))

        // The router asks a clarifying question; with auto-persistence on (the default), Koog
        // checkpoints the planner through our BoscaPersistenceStorageProvider → InMemoryKitSessionService.
        val kit = KitAgent(
            mockk<BibleService>(relaxed = true), mockk<MetadataService>(relaxed = true), mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), mockk<SqlQuery>(relaxed = true), mockk<ScriptServices>(relaxed = true), mockk<ImageServices>(relaxed = true), mockk<GraphQLService>(relaxed = true),
            getMockExecutor { mockLLMAnswer(routingJson) onRequestContains "Decide how to handle" },
            models, kitJson, sessions, mockk(relaxed = true),
        ).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("do the thing"), sessionId.toString()) }

        val asked = assertIs<KitResponse.Question>(response, "the run should complete with a Question, got: $response")
        assertEquals(question, asked.text)

        // The checkpoint round-trips back out of the provider as valid AgentCheckpointData.
        assertTrue(sessions.getCheckpoints(sessionId).isNotEmpty(), "the session should have been checkpointed")
        val latest = assertNotNull(sessions.getLatestCheckpoint(sessionId), "the latest checkpoint should be readable")
        assertTrue(latest.checkpointId.isNotBlank(), "the checkpoint should carry a real id")

        // The EventHandler recorded Kit's final, user-facing turn (the clarifying Question) for the UI —
        // exactly once, the resolved response, not internal sub-agent/tool turns.
        val recorded = sessions.getRecordedResponses(sessionId)
        assertEquals(1, recorded.size, "exactly Kit's final response should be recorded, got: $recorded")
        assertEquals(asked, recorded.single(), "the recorded response should be the Question Kit returned")
    }

    /**
     * COLLISION PROBE — does a second turn on the SAME session resume the first turn's checkpoint?
     * runId is set to the sessionId, so turn 2's `agent.run(.., sessionId)` will `rollbackToLatestCheckpoint`
     * against turn 1's stored checkpoint. Koog's safety net is the tombstone it writes on completion; this
     * proves whether that net actually holds end-to-end through our provider.
     */
    @Test
    fun `a second turn on the same session processes the new message, not a replay of the first`() = runTest {
        val sessions = InMemoryKitSessionService()
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

        // ONE shared agent (as the dispatcher uses), two turns on the SAME sessionId; the mock answers
        // by message content, so each turn has a distinct correct answer.
        val agent = KitAgent(
            mockk<BibleService>(relaxed = true), mockk<MetadataService>(relaxed = true), mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), mockk<SqlQuery>(relaxed = true), mockk<ScriptServices>(relaxed = true), mockk<ImageServices>(relaxed = true), mockk<GraphQLService>(relaxed = true),
            getMockExecutor {
                mockLLMAnswer(json.encodeToString(RouteResponse(route = KitRoute.CLARIFY, question = "Q-first"))) onRequestContains "first"
                mockLLMAnswer(json.encodeToString(RouteResponse(route = KitRoute.CLARIFY, question = "Q-second"))) onRequestContains "second"
            },
            models, kitJson, sessions, mockk(relaxed = true),
        ).agent

        val r1 = withContext(KitToolContext(auth)) { agent.run(kitRequest("the first thing"), sessionId.toString()) }
        assertEquals("Q-first", assertIs<KitResponse.Question>(r1).text)

        val r2 = withContext(KitToolContext(auth)) { agent.run(kitRequest("the second thing"), sessionId.toString()) }
        // If turn 2 resumed turn 1's checkpoint, this is still "Q-first". It must be turn 2's own answer.
        assertEquals("Q-second", assertIs<KitResponse.Question>(r2).text, "turn 2 must process its own message, not replay turn 1")
    }

    /**
     * CONTROL for the probe above. The completed-turn test can't tell us WHY there was no replay —
     * tombstone, or restore being a no-op for planner state. Here we re-feed turn 1's LIVE checkpoints
     * (tombstone dropped — exactly what an *interrupted* turn leaves) into a fresh store and run a second
     * turn whose router would answer differently. Q-first ⇒ Koog genuinely restored the planner state
     * (so interrupted-turn replay, risk #1, is real); Q-second ⇒ restore never replays the GOAP state.
     */
    @Test
    fun `a live (non-tombstone) checkpoint IS replayed by a fresh run on the same session`() = runTest {
        val sessionId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

        val store1 = InMemoryKitSessionService()
        KitAgent(
            mockk<BibleService>(relaxed = true), mockk<MetadataService>(relaxed = true), mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), mockk<SqlQuery>(relaxed = true), mockk<ScriptServices>(relaxed = true), mockk<ImageServices>(relaxed = true), mockk<GraphQLService>(relaxed = true),
            getMockExecutor { mockLLMAnswer(json.encodeToString(RouteResponse(route = KitRoute.CLARIFY, question = "Q-first"))) onRequestContains "first" },
            models, kitJson, store1, mockk(relaxed = true),
        ).agent.let { withContext(KitToolContext(auth)) { it.run(kitRequest("the first thing"), sessionId.toString()) } }

        // Drop the completion tombstone → the latest is a live checkpoint, as an interrupted turn leaves.
        val live = store1.getCheckpoints(sessionId).filterNot { it.isTombstone() }
        assertTrue(live.isNotEmpty(), "turn 1 should have produced at least one live checkpoint")
        val store2 = InMemoryKitSessionService()
        live.forEach { store2.saveCheckpoint(sessionId, it) }
        assertFalse(store2.getLatestCheckpoint(sessionId)!!.isTombstone(), "latest must be a live checkpoint")

        val r2 = KitAgent(
            mockk<BibleService>(relaxed = true), mockk<MetadataService>(relaxed = true), mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), mockk<SqlQuery>(relaxed = true), mockk<ScriptServices>(relaxed = true), mockk<ImageServices>(relaxed = true), mockk<GraphQLService>(relaxed = true),
            getMockExecutor { mockLLMAnswer(json.encodeToString(RouteResponse(route = KitRoute.CLARIFY, question = "Q-second"))) onRequestContains "second" },
            models, kitJson, store2, mockk(relaxed = true),
        ).agent.let { withContext(KitToolContext(auth)) { it.run(kitRequest("the second thing"), sessionId.toString()) } }

        // EXPERIMENT — asserting the "replay" hypothesis; the failure message reveals the real behavior.
        assertEquals("Q-first", assertIs<KitResponse.Question>(r2).text, "live checkpoint replay probe")
    }
}
