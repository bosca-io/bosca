@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.collection.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.pipeline.executeForTest
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUIDSerializer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Focused coverage for [SetCollectionReadyNode]: its [SetCollectionReadyNode.dryRun] trace branch, the
 * missing-principal `error(...)` arm of `execute`, the blank vs. non-blank `languageTag` mapping, the
 * not-found `error(...)` arm after the update, the non-Collection `resolve` guard, and a
 * (de)serialization round-trip of the `@Serializable` node so the KSP-generated serializer's
 * default-value branches are exercised.
 *
 * The `execute` happy paths (mark-ready-with-language and revoke-ready) are already covered by
 * [bosca.content.pipeline.ContentMutationNodesTest]; this file intentionally does NOT duplicate those
 * and covers everything else in the node.
 */
class SetCollectionReadyNodeCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    /** The principal a node sees on a run that carries an authenticated identity. */
    private val principal = Principal()

    /** A run with no authenticated identity — `principal()` returns null. */
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    /** A run carrying an authenticated principal, so the ready path can record an approver. */
    private val authedContext = PipelineContext(ImpersonatedAuthenticationContext(principal, emptyList()), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CollectionService> { collectionService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    /** A real [Collection] (not a mock) per the model-instance convention for domain data. */
    private fun collection(id: Uuid = Uuid.random()) = Collection(
        id = id,
        name = "Docs",
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun collectionInput(collection: Collection) =
        NodeInputs(mapOf("in" to PipelineValue.of(collection, Collection.serializer())))

    // ── dryRun ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run records the intended action, passes the input through, and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val input = collectionInput(collection())
        val passed = input.first

        val out = SetCollectionReadyNode(id = "rd", ready = true)
            .executeForTest(dry, input)

        assertSame(passed, out)
        val action = trace.actions["rd"]
        assertNotNull(action)
        assertEquals("setCollectionReady", action.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals(true, action.jsonObject["ready"]?.jsonPrimitive?.boolean)
        coVerify(exactly = 0) { collectionService.setReady(any<Uuid>(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setNotReady(any()) }
        coVerify(exactly = 0) { collectionService.getById(any()) }
    }

    @Test
    fun `dry run records the revoke-ready flag when ready is off`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        SetCollectionReadyNode(id = "off", ready = false).executeForTest(dry, collectionInput(collection()))

        val action = trace.actions["off"]
        assertNotNull(action)
        assertEquals(false, action.jsonObject["ready"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `dry run without a trace records nothing but still passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val input = collectionInput(collection())
        val passed = input.first

        val out = SetCollectionReadyNode(id = "rd").executeForTest(dry, input)

        assertSame(passed, out)
    }

    @Test
    fun `dry run tolerates a missing input and still records the action`() = runTest {
        // The dry run reads the raw inbound value only — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SetCollectionReadyNode(id = "the-id").executeForTest(dry, NodeInputs(emptyMap()))

        assertNull(out)
        assertNotNull(trace.actions["the-id"])
    }

    // ── execute: ready path ───────────────────────────────────────────────────────────────────────

    @Test
    fun `mark ready with a blank language tag targets the base collection`() = runTest {
        val id = Uuid.random()
        val fresh = collection(id)
        coEvery { collectionService.getById(id) } returns fresh

        // languageTag defaults to "" → languageTag.ifBlank { null } passes null.
        val out = SetCollectionReadyNode(id = "n", ready = true)
            .executeForTest(authedContext, collectionInput(collection(id)))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { collectionService.setReady(id, principal, null) }
    }

    @Test
    fun `mark ready fails without an authenticated principal, naming the node`() = runTest {
        val node = SetCollectionReadyNode(id = "n", name = "Approve", ready = true)

        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, collectionInput(collection()))
        }

        assertTrue(e.message?.contains("Approve") == true)
        assertTrue(e.message?.contains("requires an authenticated principal") == true)
        coVerify(exactly = 0) { collectionService.setReady(any<Uuid>(), any(), any()) }
    }

    @Test
    fun `mark ready without a principal uses the node id in the message when name is blank`() = runTest {
        val node = SetCollectionReadyNode(id = "blank-name-id", ready = true)

        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, collectionInput(collection()))
        }

        assertTrue(e.message?.contains("blank-name-id") == true)
    }

    // ── execute: error arms ───────────────────────────────────────────────────────────────────────

    @Test
    fun `execute fails clearly when the collection is gone after the update`() = runTest {
        val id = Uuid.random()
        coEvery { collectionService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetCollectionReadyNode(id = "n", name = "Approve It", ready = false)
                .executeForTest(authedContext, collectionInput(collection(id)))
        }

        assertTrue(e.message?.contains("Approve It") == true)
        assertTrue(e.message?.contains("not found after update") == true)
        coVerify(exactly = 1) { collectionService.setNotReady(any()) }
    }

    @Test
    fun `execute uses the node id in the not-found message when the name is blank`() = runTest {
        val id = Uuid.random()
        coEvery { collectionService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetCollectionReadyNode(id = "gone-id", ready = false)
                .executeForTest(authedContext, collectionInput(collection(id)))
        }

        assertTrue(e.message?.contains("gone-id") == true)
    }

    @Test
    fun `execute rejects a non-collection input`() = runTest {
        // The generated deserialize bridges through Collection.serializer(), so a non-Collection
        // value fails decoding.
        assertFailsWith<SerializationException> {
            SetCollectionReadyNode(id = "n", name = "Ready")
                .executeForTest(authedContext, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("x")))))
        }
    }

    @Test
    fun `execute rejects an empty input with the generated required-input message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            SetCollectionReadyNode(id = "empty")
                .executeForTest(authedContext, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── serializer round-trip (covers KSP-generated default-value branches) ─────────────────────

    @Test
    fun `serialization round-trips a fully-specified node`() {
        val node = SetCollectionReadyNode(
            id = "node-1",
            name = "Set Ready",
            description = "Approve for publishing",
            ready = false,
            languageTag = "fr",
            position = NodePosition(x = 12.0, y = 34.0),
        )

        val encoded = json.encodeToString(SetCollectionReadyNode.serializer(), node)
        val decoded = json.decodeFromString(SetCollectionReadyNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.ready, decoded.ready)
        assertEquals(node.languageTag, decoded.languageTag)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `serialization round-trips a defaults-only node`() {
        val node = SetCollectionReadyNode(id = "defaults")

        val encoded = json.encodeToString(SetCollectionReadyNode.serializer(), node)
        val decoded = json.decodeFromString(SetCollectionReadyNode.serializer(), encoded)

        assertEquals("defaults", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertTrue(decoded.ready)
        assertEquals("", decoded.languageTag)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
        assertNull(decoded.rollbackPipeline)
    }

    @Test
    fun `serialization decodes a minimal object relying on defaults`() {
        val decoded = json.decodeFromString(
            SetCollectionReadyNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertTrue(decoded.ready)
        assertEquals("", decoded.languageTag)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `constructor exposes its declared property values`() {
        val node = SetCollectionReadyNode(
            id = "props",
            name = "n",
            description = "d",
            ready = false,
            languageTag = "es",
            position = NodePosition(x = 1.0, y = 2.0),
        )

        assertEquals("props", node.id)
        assertEquals("n", node.name)
        assertEquals("d", node.description)
        assertEquals(false, node.ready)
        assertEquals("es", node.languageTag)
        assertEquals(NodePosition(x = 1.0, y = 2.0), node.position)
    }
}
