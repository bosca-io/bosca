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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * Focused coverage for [SetMetadataAttributesNode]: the paths the shared
 * `ContentMutationNodesTest` leaves untouched — the [SetMetadataAttributesNode.dryRun] trace (both
 * merge variants), the blank/non-blank display-label branches, the not-found-after-update guard, the
 * non-Metadata `in` rejection, and a `@Serializable` round-trip that exercises the constructor
 * defaults and settings shape. The happy merge/replace + missing-`attributes` arms are already
 * covered there; this only fills the remaining branches.
 */
class SetMetadataAttributesNodeCoverageTest {

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

    private fun inputs(metadata: Metadata, attributes: PipelineValue) = NodeInputs(
        mapOf(
            "in" to PipelineValue.of(metadata, Metadata.serializer()),
            "attributes" to attributes,
        ),
    )

    // ── dryRun ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dry run with merge on records the intent, mutates nothing, and passes the metadata through`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()
        val attrs = buildJsonObject { put("k", "v") }

        val node = SetMetadataAttributesNode(id = "attrs", merge = true)
        val out = node.executeForTest(dry, inputs(metadata, PipelineValue.ofJson(attrs)))

        // The `in` Metadata is passed straight through (the same wrapped value, not the fresh fetch).
        assertSame(metadata, out?.value)
        val recorded = trace.actions["attrs"]
        assertTrue(recorded != null)
        // No service side effects under dry run.
        coVerify(exactly = 0) { metadataService.mergeAttributes(any<Metadata>(), any()) }
        coVerify(exactly = 0) { metadataService.setAttributes(any<Metadata>(), any()) }
        coVerify(exactly = 0) { metadataService.getById(any<Uuid>()) }
    }

    @Test
    fun `dry run records the merge flag as false when merge is off`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()

        SetMetadataAttributesNode(id = "attrs", merge = false)
            .executeForTest(dry, inputs(metadata, PipelineValue.ofJson(buildJsonObject { put("k", "v") })))

        val recorded = trace.actions["attrs"] as? JsonObject
        assertTrue(recorded != null)
        // The recorded action carries action + merge fields; merge reflects the node's flag.
        assertEquals("false", recorded?.get("merge")?.jsonPrimitive?.content)
        assertEquals("setMetadataAttributes", recorded?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `dry run tolerates a missing attributes input, resolving only the in port`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        val metadata = metadataMock()

        // dryRun only resolves `in`; a missing `attributes` port must not blow up the trace.
        val out = SetMetadataAttributesNode(id = "attrs")
            .executeForTest(dry, NodeInputs(mapOf("in" to PipelineValue.of(metadata, Metadata.serializer()))))

        assertSame(metadata, out?.value)
        assertTrue(trace.actions.containsKey("attrs"))
    }

    @Test
    fun `dry run without a trace records nothing but still passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)
        val metadata = metadataMock()

        val out = SetMetadataAttributesNode(id = "attrs")
            .executeForTest(dry, NodeInputs(mapOf("in" to PipelineValue.of(metadata, Metadata.serializer()))))

        assertSame(metadata, out?.value)
    }

    // ── execute: not-found guard + label branches ─────────────────────────────────────────────────

    @Test
    fun `execute fails when the metadata cannot be re-fetched after the update`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns null
        val attrs = buildJsonObject { put("k", "v") }

        val node = SetMetadataAttributesNode(id = "n", name = "Write Attrs", merge = true)
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, inputs(metadata, PipelineValue.ofJson(attrs)))
        }
        // Non-blank name is used as the label and the id is quoted in the not-found message.
        assertTrue(e.message?.contains("Write Attrs") == true)
        assertTrue(e.message?.contains(id.toString()) == true)
        coVerify(exactly = 1) { metadataService.mergeAttributes(metadata, attrs) }
    }

    @Test
    fun `execute falls back to the node id as the label when name is blank`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getById(id) } returns null

        // Blank name → label is the node id, exercised via the not-found error message.
        val e = assertFailsWith<IllegalStateException> {
            SetMetadataAttributesNode(id = "node-42")
                .executeForTest(context, inputs(metadata, PipelineValue.ofJson(buildJsonObject { put("k", "v") })))
        }
        assertTrue(e.message?.contains("node-42") == true)
    }

    @Test
    fun `execute encodes a non-object attributes input and passes it through to the service`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val fresh = metadataMock()
        coEvery { metadataService.getById(id) } returns fresh
        val attrs = JsonPrimitive("scalar")

        val out = SetMetadataAttributesNode(id = "n", merge = true)
            .executeForTest(context, inputs(metadata, PipelineValue.ofJson(attrs)))

        assertSame(fresh, out?.value)
        // encode() re-serializes the JsonElement to itself, so the scalar reaches mergeAttributes intact.
        coVerify(exactly = 1) { metadataService.mergeAttributes(metadata, attrs) }
    }

    // ── resolve: input rejection + label in that message ──────────────────────────────────────────

    @Test
    fun `execute rejects an in port that is not a Metadata`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        val node = SetMetadataAttributesNode(id = "the-node")
        assertFailsWith<SerializationException> {
            node.executeForTest(
                context,
                NodeInputs(
                    mapOf(
                        "in" to PipelineValue.ofJson(JsonPrimitive("not a metadata")),
                        "attributes" to PipelineValue.ofJson(buildJsonObject { put("k", "v") }),
                    ),
                ),
            )
        }
    }

    @Test
    fun `execute rejects a missing in port with the generated required-input message`() = runTest {
        val node = SetMetadataAttributesNode(id = "n", name = "Apply Attributes")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(
                context,
                NodeInputs(mapOf("attributes" to PipelineValue.ofJson(buildJsonObject { put("k", "v") }))),
            )
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    @Test
    fun `execute rejects a missing attributes port with the generated required-input message`() = runTest {
        val node = SetMetadataAttributesNode(id = "n")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(
                context,
                NodeInputs(mapOf("in" to PipelineValue.of(metadata(), Metadata.serializer()))),
            )
        }
        assertTrue(e.message?.contains("required input 'attributes'") == true)
    }

    @Test
    fun `dry run passes a non-Metadata in port through untouched`() = runTest {
        // The dry run reads the raw inbound value only — it neither decodes nor validates it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SetMetadataAttributesNode(id = "n")
            .executeForTest(dry, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))))

        assertEquals(JsonPrimitive("nope"), out?.value)
        assertTrue(trace.actions.containsKey("n"))
    }

    // ── @Serializable round-trip: constructor defaults + settings shape ───────────────────────────

    @Test
    fun `node round-trips through its serializer preserving the merge flag and position`() {
        val node = SetMetadataAttributesNode(
            id = "n1",
            name = "Attr Writer",
            description = "writes attrs",
            merge = false,
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(SetMetadataAttributesNode.serializer(), node)
        val decoded = json.decodeFromString(SetMetadataAttributesNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.merge, decoded.merge)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node applies its constructor defaults when only the id is supplied`() {
        val node = SetMetadataAttributesNode(id = "bare")

        assertEquals("bare", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        // merge defaults to a deep-merge; position defaults to the origin.
        assertTrue(node.merge)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `decoding a minimal graph fragment fills every default`() {
        val decoded = json.decodeFromString(
            SetMetadataAttributesNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertTrue(decoded.merge)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
    }
}
