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
import bosca.security.service.AuthenticationContext
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
 * Focused coverage for [SetCollectionSearchableNode]: its [SetCollectionSearchableNode.dryRun] trace
 * branch, the not-found `error(...)` arm of `execute`, the non-Collection `resolve` guard, and a
 * (de)serialization round-trip of the `@Serializable` node so the KSP-generated serializer's
 * default-value branches are exercised.
 *
 * The `execute` happy path (update + re-fetch) is already covered by
 * [bosca.content.pipeline.ContentMutationNodesTest]; this file intentionally does NOT duplicate it and
 * covers everything else in the node.
 */
class SetCollectionSearchableNodeCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val context = PipelineContext(AuthenticationContext(null, null), json)

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

        val out = SetCollectionSearchableNode(id = "sc", searchable = false)
            .executeForTest(dry, input)

        assertSame(passed, out)
        val action = trace.actions["sc"]
        assertNotNull(action)
        assertEquals("setCollectionSearchable", action.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals(false, action.jsonObject["searchable"]?.jsonPrimitive?.boolean)
        coVerify(exactly = 0) { collectionService.setSearchable(any(), any()) }
        coVerify(exactly = 0) { collectionService.getById(any()) }
    }

    @Test
    fun `dry run records the searchable-on flag when default is used`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        SetCollectionSearchableNode(id = "on").executeForTest(dry, collectionInput(collection()))

        val action = trace.actions["on"]
        assertNotNull(action)
        assertEquals(true, action.jsonObject["searchable"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `dry run without a trace records nothing but still passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val input = collectionInput(collection())
        val passed = input.first

        val out = SetCollectionSearchableNode(id = "sc").executeForTest(dry, input)

        assertSame(passed, out)
    }

    @Test
    fun `dry run tolerates a missing input and still records the action`() = runTest {
        // The dry run reads the raw inbound value only — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SetCollectionSearchableNode(id = "the-id").executeForTest(dry, NodeInputs(emptyMap()))

        assertNull(out)
        assertNotNull(trace.actions["the-id"])
    }

    // ── execute error arms ──────────────────────────────────────────────────────────────────────

    @Test
    fun `execute fails clearly when the collection is gone after the update`() = runTest {
        val id = Uuid.random()
        coEvery { collectionService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetCollectionSearchableNode(id = "n", name = "Index It", searchable = true)
                .executeForTest(context, collectionInput(collection(id)))
        }
        assertTrue(e.message?.contains("Index It") == true)
        assertTrue(e.message?.contains("not found after update") == true)
        coVerify(exactly = 1) { collectionService.setSearchable(id, true) }
    }

    @Test
    fun `execute uses the node id in the not-found message when the name is blank`() = runTest {
        val id = Uuid.random()
        coEvery { collectionService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetCollectionSearchableNode(id = "blank-name-id")
                .executeForTest(context, collectionInput(collection(id)))
        }
        assertTrue(e.message?.contains("blank-name-id") == true)
    }

    @Test
    fun `execute rejects a non-collection input`() = runTest {
        // The generated deserialize bridges through Collection.serializer(), so a non-Collection
        // value fails decoding.
        assertFailsWith<SerializationException> {
            SetCollectionSearchableNode(id = "n", name = "Searchable")
                .executeForTest(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("x")))))
        }
    }

    @Test
    fun `execute rejects an empty input with the generated required-input message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            SetCollectionSearchableNode(id = "empty")
                .executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── serializer round-trip (covers KSP-generated default-value branches) ─────────────────────

    @Test
    fun `serialization round-trips a fully-specified node`() {
        val node = SetCollectionSearchableNode(
            id = "node-1",
            name = "Set Searchable",
            description = "Toggle search visibility",
            searchable = false,
            position = NodePosition(x = 12.0, y = 34.0),
        )

        val encoded = json.encodeToString(SetCollectionSearchableNode.serializer(), node)
        val decoded = json.decodeFromString(SetCollectionSearchableNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.searchable, decoded.searchable)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `serialization round-trips a defaults-only node`() {
        val node = SetCollectionSearchableNode(id = "defaults")

        val encoded = json.encodeToString(SetCollectionSearchableNode.serializer(), node)
        val decoded = json.decodeFromString(SetCollectionSearchableNode.serializer(), encoded)

        assertEquals("defaults", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertTrue(decoded.searchable)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
        assertNull(decoded.rollbackPipeline)
    }

    @Test
    fun `serialization decodes a minimal object relying on defaults`() {
        val decoded = json.decodeFromString(
            SetCollectionSearchableNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertTrue(decoded.searchable)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `constructor exposes its declared property values`() {
        val node = SetCollectionSearchableNode(
            id = "props",
            name = "n",
            description = "d",
            searchable = false,
            position = NodePosition(x = 1.0, y = 2.0),
        )

        assertEquals("props", node.id)
        assertEquals("n", node.name)
        assertEquals("d", node.description)
        assertEquals(false, node.searchable)
        assertEquals(NodePosition(x = 1.0, y = 2.0), node.position)
    }
}
