@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.pipeline.executeForTest
import bosca.content.pipeline.executeForTestValue
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Focused coverage for [TransitionMetadataNode]: the dry-run trace path, the execute path with and
 * without an authenticated principal, the input/state validation error arms (both branches of the
 * `name.ifBlank { id }` message helper), and a serializer round-trip that exercises the generated
 * defaulted-vs-supplied constructor branches.
 *
 * (Complements `bosca.content.pipeline.ContentMutationNodesTest`, which covers only the happy execute
 * path and the blank-state failure; the branches below are the ones it leaves uncovered.)
 */
class TransitionMetadataNodeCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val principal = Principal()
    private val context = PipelineContext(AuthenticationContext(null, null), json)
    private val authedContext = PipelineContext(ImpersonatedAuthenticationContext(principal, emptyList()), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MetadataService> { metadataService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadataMock(id: Uuid = Uuid.random()) = mockk<Metadata> { every { this@mockk.id } returns id }

    /** A real [Metadata] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun metadata(id: Uuid = Uuid.random()) = Metadata(
        id = id,
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )
    private fun metadataInput(metadata: Metadata) =
        NodeInputs(mapOf("in" to PipelineValue.of(metadata, Metadata.serializer())))

    // ── execute ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute passes the run principal through and returns the transitioned metadata`() = runTest {
        val metadata = metadata()
        val updated = mockk<Metadata>()
        coEvery { metadataService.setState(metadata, "published", "note", principal) } returns updated

        val out = TransitionMetadataNode(id = "n", state = "published", status = "note")
            .executeForTestValue(authedContext, metadataInput(metadata))

        assertSame(updated, out.value)
        coVerify(exactly = 1) { metadataService.setState(metadata, "published", "note", principal) }
    }

    @Test
    fun `execute passes a null principal when the run is unauthenticated`() = runTest {
        val metadata = metadata()
        val updated = mockk<Metadata>()
        coEvery { metadataService.setState(metadata, "published", "", null) } returns updated

        val out = TransitionMetadataNode(id = "n", state = "published")
            .executeForTestValue(context, metadataInput(metadata))

        assertSame(updated, out.value)
        coVerify(exactly = 1) { metadataService.setState(metadata, "published", "", null) }
    }

    @Test
    fun `execute trims a padded state before transitioning`() = runTest {
        val metadata = metadata()
        val updated = mockk<Metadata>()
        coEvery { metadataService.setState(metadata, "published", "", null) } returns updated

        TransitionMetadataNode(id = "n", state = "  published  ")
            .executeForTestValue(context, metadataInput(metadata))

        coVerify(exactly = 1) { metadataService.setState(metadata, "published", "", null) }
    }

    // ── validation error arms ──────────────────────────────────────────────────────────────────────

    @Test
    fun `execute requires a target state and names the node by its display name`() = runTest {
        val node = TransitionMetadataNode(id = "n", name = "Move", state = "   ")
        val e = assertFailsWith<IllegalArgumentException> {
            node.executeForTest(authedContext, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("Move") == true)
        assertTrue(e.message?.contains("requires a target state") == true)
    }

    @Test
    fun `execute requires a target state and falls back to the id when unnamed`() = runTest {
        val node = TransitionMetadataNode(id = "node-42", state = "")
        val e = assertFailsWith<IllegalArgumentException> {
            node.executeForTest(authedContext, metadataInput(metadata()))
        }
        assertTrue(e.message?.contains("node-42") == true)
    }

    @Test
    fun `execute rejects a non-metadata input`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        val node = TransitionMetadataNode(id = "n", name = "Move", state = "published")
        val inputs = NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope"))))
        assertFailsWith<SerializationException> {
            node.executeForTest(authedContext, inputs)
        }
        coVerify(exactly = 0) { metadataService.setState(any(), any(), any(), any()) }
    }

    @Test
    fun `execute rejects an absent input with the generated required-input message`() = runTest {
        val node = TransitionMetadataNode(id = "solo", state = "published")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(authedContext, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── dry run ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run records the intended transition and passes the input through without mutating`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()
        val inputValue = PipelineValue.of(metadata, Metadata.serializer())

        val out = TransitionMetadataNode(id = "tr", state = "  published  ", status = "note")
            .executeForTest(dryContext, NodeInputs(mapOf("in" to inputValue)))

        assertSame(inputValue, out)
        val recorded = trace.actions["tr"]?.jsonObject
        assertEquals("transitionMetadata", recorded?.get("action")?.jsonPrimitive?.content)
        assertEquals("published", recorded?.get("state")?.jsonPrimitive?.content)
        assertEquals("note", recorded?.get("status")?.jsonPrimitive?.content)
        coVerify(exactly = 0) { metadataService.setState(any(), any(), any(), any()) }
    }

    @Test
    fun `dry run without a trace still returns the input and mutates nothing`() = runTest {
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val metadata = metadataMock()
        val inputValue = PipelineValue.of(metadata, Metadata.serializer())

        val out = TransitionMetadataNode(id = "tr", state = "published")
            .executeForTest(dryContext, NodeInputs(mapOf("in" to inputValue)))

        assertSame(inputValue, out)
        coVerify(exactly = 0) { metadataService.setState(any(), any(), any(), any()) }
    }

    @Test
    fun `dry run still enforces a target state`() = runTest {
        val trace = DryRunTrace()
        val dryContext = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val node = TransitionMetadataNode(id = "tr", name = "Move", state = " ")

        assertFailsWith<IllegalArgumentException> {
            node.executeForTest(dryContext, metadataInput(metadata()))
        }
        assertTrue(trace.actions.isEmpty())
    }

    // ── serializer round-trip (generated defaulted-vs-supplied branches) ────────────────────────────

    @Test
    fun `serializer round-trips a fully specified node preserving every field`() {
        val original = TransitionMetadataNode(
            id = "id-1",
            name = "Publish",
            description = "moves it to published",
            state = "published",
            status = "by pipeline",
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(TransitionMetadataNode.serializer(), original)
        val decoded = json.decodeFromString(TransitionMetadataNode.serializer(), encoded)

        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.description, decoded.description)
        assertEquals(original.state, decoded.state)
        assertEquals(original.status, decoded.status)
        assertEquals(original.position, decoded.position)
    }

    @Test
    fun `serializer round-trips a node built purely from defaults`() {
        val original = TransitionMetadataNode(id = "id-2")

        val encoded = json.encodeToString(TransitionMetadataNode.serializer(), original)
        val decoded = json.decodeFromString(TransitionMetadataNode.serializer(), encoded)

        assertEquals("id-2", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals("", decoded.state)
        assertEquals("", decoded.status)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `serializer round-trips a node with only some fields overridden`() {
        val original = TransitionMetadataNode(id = "id-3", state = "review")

        val encoded = json.encodeToString(TransitionMetadataNode.serializer(), original)
        val decoded = json.decodeFromString(TransitionMetadataNode.serializer(), encoded)

        assertEquals("id-3", decoded.id)
        assertEquals("review", decoded.state)
        assertEquals("", decoded.status)
        assertEquals("", decoded.name)
    }

    @Test
    fun `decode tolerates a payload carrying only the required id`() {
        val decoded = json.decodeFromString(TransitionMetadataNode.serializer(), """{"id":"id-4"}""")

        assertEquals("id-4", decoded.id)
        assertEquals("", decoded.state)
        assertEquals("", decoded.status)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `distinct field values encode to distinct payloads`() {
        val a = TransitionMetadataNode(id = "x", state = "published", status = "a")
        val b = TransitionMetadataNode(id = "x", state = "draft", status = "b")

        val encodedA = json.encodeToString(TransitionMetadataNode.serializer(), a)
        val encodedB = json.encodeToString(TransitionMetadataNode.serializer(), b)

        assertNotEquals(encodedA, encodedB)
    }
}
