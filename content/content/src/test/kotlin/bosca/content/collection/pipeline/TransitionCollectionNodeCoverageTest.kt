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
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Exhaustive coverage for [TransitionCollectionNode]. The one happy-path case lives in
 * `ContentMutationNodesTest`; this file drives the remaining arms: dry-run tracing, the unauthenticated
 * (null-principal) execute path, the not-found-after-transition error, the blank-state `require`, the
 * non-Collection / empty-input `resolve` errors, the `name.ifBlank { id }` label branches, and a direct
 * `@Serializable`/`@SerialName` round-trip of the node's settings.
 */
class TransitionCollectionNodeCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val principal = Principal()
    private val context = PipelineContext(AuthenticationContext(null, null), json)
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

    private fun collectionInput(collection: Collection) =
        NodeInputs(mapOf("in" to PipelineValue.of(collection, Collection.serializer())))

    /** A real [Collection] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun collection(id: Uuid = Uuid.random()) = Collection(
        id = id,
        name = "Docs",
        languageTag = "en",
        workflowStateId = "draft",
    )

    // ── execute() ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute transitions with a null principal when unauthenticated`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        val fresh = mockk<Collection>()
        coEvery { collectionService.setState(collection, "published", "note", null) } returns mockk()
        coEvery { collectionService.getById(id) } returns fresh

        val out = TransitionCollectionNode(id = "n", state = "published", status = "note")
            .executeForTest(context, collectionInput(collection))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { collectionService.setState(collection, "published", "note", null) }
    }

    @Test
    fun `execute trims the target state before transitioning`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        coEvery { collectionService.setState(collection, "published", "", principal) } returns mockk()
        coEvery { collectionService.getById(id) } returns mockk<Collection>()

        TransitionCollectionNode(id = "n", state = "  published  ")
            .executeForTest(authedContext, collectionInput(collection))

        coVerify(exactly = 1) { collectionService.setState(collection, "published", "", principal) }
    }

    @Test
    fun `execute fails when the collection is gone after the transition`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        coEvery { collectionService.setState(collection, "published", "", principal) } returns mockk()
        coEvery { collectionService.getById(id) } returns null

        val node = TransitionCollectionNode(id = "n", name = "Ship", state = "published")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(authedContext, collectionInput(collection))
        }
        assertTrue(e.message?.contains("Ship") == true)
        assertTrue(e.message?.contains("not found after transition") == true)
    }

    @Test
    fun `execute uses the node id in the not-found message when unnamed`() = runTest {
        val id = Uuid.random()
        val collection = collection(id)
        coEvery { collectionService.setState(collection, "published", "", principal) } returns mockk()
        coEvery { collectionService.getById(id) } returns null

        val node = TransitionCollectionNode(id = "node-42", state = "published")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(authedContext, collectionInput(collection))
        }
        assertTrue(e.message?.contains("node-42") == true)
    }

    // ── resolveState() require ──────────────────────────────────────────────────────────────────────

    @Test
    fun `execute requires a non-blank target state`() = runTest {
        val node = TransitionCollectionNode(id = "n", name = "Move", state = "   ")
        val e = assertFailsWith<IllegalArgumentException> {
            node.executeForTest(authedContext, collectionInput(collection()))
        }
        assertTrue(e.message?.contains("Move") == true)
        assertTrue(e.message?.contains("requires a target state") == true)
    }

    @Test
    fun `execute state require uses id when name is blank`() = runTest {
        val node = TransitionCollectionNode(id = "the-node", state = "")
        val e = assertFailsWith<IllegalArgumentException> {
            node.executeForTest(authedContext, collectionInput(collection()))
        }
        assertTrue(e.message?.contains("the-node") == true)
    }

    // ── input decoding ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute rejects a non-Collection input`() = runTest {
        // The generated deserialize bridges through Collection.serializer(), so a non-Collection
        // value fails decoding.
        val node = TransitionCollectionNode(id = "n", name = "Trans", state = "published")
        assertFailsWith<SerializationException> {
            node.executeForTest(authedContext, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))))
        }
    }

    @Test
    fun `execute rejects empty inputs with the generated required-input message`() = runTest {
        val node = TransitionCollectionNode(id = "empty-in", state = "published")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(authedContext, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── dryRun() ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run records the intended transition and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val collection = collection()
        val value = PipelineValue.of(collection, Collection.serializer())

        val out = TransitionCollectionNode(id = "dry", state = "  published  ", status = "note")
            .executeForTest(dryContext, NodeInputs(mapOf("in" to value)))

        assertSame(collection, out?.value)
        assertTrue(trace.actions.containsKey("dry"))
        coVerify(exactly = 0) { collectionService.setState(any(), any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.getById(any()) }
    }

    @Test
    fun `dry run without a trace still returns the input and mutates nothing`() = runTest {
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = null)
        val collection = collection()
        val value = PipelineValue.of(collection, Collection.serializer())

        val out = TransitionCollectionNode(id = "dry", state = "published")
            .executeForTest(dryContext, NodeInputs(mapOf("in" to value)))

        assertSame(collection, out?.value)
        coVerify(exactly = 0) { collectionService.setState(any(), any(), any(), any()) }
    }

    @Test
    fun `dry run still requires a target state`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val collection = collection()
        val value = PipelineValue.of(collection, Collection.serializer())

        val node = TransitionCollectionNode(id = "dry", name = "DryMove", state = " ")
        assertFailsWith<IllegalArgumentException> {
            node.executeForTest(dryContext, NodeInputs(mapOf("in" to value)))
        }
        assertNull(trace.actions["dry"])
    }

    @Test
    fun `dry run tolerates a missing input and still records the transition`() = runTest {
        // The dry run reads the raw inbound value only — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val node = TransitionCollectionNode(id = "dry", state = "published")
        val out = node.executeForTest(dryContext, NodeInputs(emptyMap()))

        assertNull(out)
        assertTrue(trace.actions.containsKey("dry"))
    }

    // ── @Serializable / @SerialName round-trip ──────────────────────────────────────────────────────

    @Test
    fun `settings round-trip preserves every field`() {
        val node = TransitionCollectionNode(
            id = "n1",
            name = "Publish it",
            description = "moves to published",
            state = "published",
            status = "done by pipeline",
            position = NodePosition(12.5, -3.0),
        )

        val encoded = json.encodeToString(TransitionCollectionNode.serializer(), node)
        val decoded = json.decodeFromString(TransitionCollectionNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.state, decoded.state)
        assertEquals(node.status, decoded.status)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `settings round-trip preserves defaulted fields`() {
        val node = TransitionCollectionNode(id = "n2")

        val encoded = json.encodeToString(TransitionCollectionNode.serializer(), node)
        val decoded = json.decodeFromString(TransitionCollectionNode.serializer(), encoded)

        assertEquals("n2", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals("", decoded.state)
        assertEquals("", decoded.status)
        assertEquals(NodePosition(), decoded.position)
    }
}
