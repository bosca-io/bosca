@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
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
import io.mockk.every
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
 * Focused coverage for [SetMetadataSearchableNode]: the paths the shared
 * [bosca.content.pipeline.ContentMutationNodesTest] leaves untouched — the
 * [SetMetadataSearchableNode.dryRun] trace branch (searchable on/off, with and without a trace, and the
 * non-Metadata guard), the not-found `error(...)` arm of `execute` (named and blank-name label
 * variants), the non-Metadata and empty-input `resolve` guards, and a (de)serialization round-trip of
 * the `@Serializable` node so the KSP-generated serializer's default-value branches are exercised.
 *
 * The `execute` happy path (update + re-fetch, searchable = false) is already covered by
 * `ContentMutationNodesTest`; this file intentionally does NOT duplicate it.
 */
class SetMetadataSearchableNodeCoverageTest {

    private val metadataService = mockk<MetadataService>(relaxUnitFun = true)

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val context = PipelineContext(AuthenticationContext(null, null), json)

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

    /** Metadata is a large model, so mock it and stub only the `id` the node reads (per the sibling convention). */
    private fun metadataMock(id: Uuid = Uuid.random()) =
        mockk<Metadata> { every { this@mockk.id } returns id }

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

    // ── dryRun ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run records the intended action, passes the input through, and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val input = metadataInput(metadataMock())
        val passed = input.first

        val out = SetMetadataSearchableNode(id = "sm", searchable = false)
            .executeForTest(dry, input)

        // dryRun returns the resolved input value straight through.
        assertSame(passed, out)
        val action = trace.actions["sm"]
        assertNotNull(action)
        assertEquals("setMetadataSearchable", action.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals(false, action.jsonObject["searchable"]?.jsonPrimitive?.boolean)
        coVerify(exactly = 0) { metadataService.setSearchable(any(), any()) }
        coVerify(exactly = 0) { metadataService.getById(any<Uuid>()) }
    }

    @Test
    fun `dry run records the searchable-on flag when the default is used`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        SetMetadataSearchableNode(id = "on").executeForTest(dry, metadataInput(metadataMock()))

        val action = trace.actions["on"]
        assertNotNull(action)
        assertEquals(true, action.jsonObject["searchable"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `dry run without a trace records nothing but still passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val input = metadataInput(metadataMock())
        val passed = input.first

        val out = SetMetadataSearchableNode(id = "sm").executeForTest(dry, input)

        assertSame(passed, out)
    }

    @Test
    fun `dry run tolerates a missing input and still records the action`() = runTest {
        // The dry run reads the raw inbound value only — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SetMetadataSearchableNode(id = "the-id").executeForTest(dry, NodeInputs(emptyMap()))

        assertNull(out)
        assertNotNull(trace.actions["the-id"])
    }

    // ── execute error arms ──────────────────────────────────────────────────────────────────────

    @Test
    fun `execute fails clearly when the metadata is gone after the update, using the name as label`() = runTest {
        val id = Uuid.random()
        coEvery { metadataService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetMetadataSearchableNode(id = "n", name = "Index It", searchable = true)
                .executeForTest(context, metadataInput(metadata(id)))
        }
        assertTrue(e.message?.contains("Index It") == true)
        assertTrue(e.message?.contains(id.toString()) == true)
        assertTrue(e.message?.contains("not found after update") == true)
        coVerify(exactly = 1) { metadataService.setSearchable(id, true) }
    }

    @Test
    fun `execute uses the node id in the not-found message when the name is blank`() = runTest {
        val id = Uuid.random()
        coEvery { metadataService.getById(id) } returns null

        val e = assertFailsWith<IllegalStateException> {
            SetMetadataSearchableNode(id = "blank-name-id")
                .executeForTest(context, metadataInput(metadata(id)))
        }
        assertTrue(e.message?.contains("blank-name-id") == true)
    }

    @Test
    fun `execute rejects a non-metadata input`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        assertFailsWith<SerializationException> {
            SetMetadataSearchableNode(id = "n", name = "Searchable")
                .executeForTest(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("x")))))
        }
    }

    @Test
    fun `execute rejects an empty input with the generated required-input message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            SetMetadataSearchableNode(id = "empty")
                .executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    @Test
    fun `execute passes the refreshed metadata through on the happy path with default searchable`() = runTest {
        val id = Uuid.random()
        val fresh = metadataMock()
        coEvery { metadataService.getById(id) } returns fresh

        val out = SetMetadataSearchableNode(id = "n")
            .executeForTest(context, metadataInput(metadata(id)))

        assertSame(fresh, out?.value)
        coVerify(exactly = 1) { metadataService.setSearchable(id, true) }
    }

    // ── serializer round-trip (covers KSP-generated default-value branches) ─────────────────────

    @Test
    fun `serialization round-trips a fully-specified node`() {
        val node = SetMetadataSearchableNode(
            id = "node-1",
            name = "Set Searchable",
            description = "Toggle search visibility",
            searchable = false,
            position = NodePosition(x = 12.0, y = 34.0),
        )

        val encoded = json.encodeToString(SetMetadataSearchableNode.serializer(), node)
        val decoded = json.decodeFromString(SetMetadataSearchableNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.searchable, decoded.searchable)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `serialization round-trips a defaults-only node`() {
        val node = SetMetadataSearchableNode(id = "defaults")

        val encoded = json.encodeToString(SetMetadataSearchableNode.serializer(), node)
        val decoded = json.decodeFromString(SetMetadataSearchableNode.serializer(), encoded)

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
            SetMetadataSearchableNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertTrue(decoded.searchable)
        assertEquals(NodePosition(), decoded.position)
    }

    @Test
    fun `constructor exposes its declared property values`() {
        val node = SetMetadataSearchableNode(
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
