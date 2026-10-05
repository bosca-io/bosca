@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
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
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Focused coverage for [MetadataEventToMetadataNode]: the branches the shared
 * `ContentResolverNodesTest` leaves untouched — the [MetadataEventToMetadataNode.execute] missing-UUID
 * error arm (both the blank-name→id and non-blank-name label branches), the dry-run path (a
 * [bosca.pipelines.node.TransformNode] dry-runs by re-running `execute`), and the generated
 * `@Serializable`/`@SerialName("metadata.fromEvent")` encode/decode branches driven by the four
 * constructor defaults (`name`, `description`, `position`). The happy-path resolve and the
 * not-found arm with a non-blank name are already covered there; this fills the remaining branches.
 */
class MetadataEventToMetadataNodeCoverageTest {

    private val metadataService = mockk<MetadataService>()

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

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("in" to value))

    // ── execute: happy path + not-found + missing-UUID label branches ─────────────────────────────

    @Test
    fun `resolves the metadata for a uuid value and carries it out`() = runTest {
        val id = Uuid.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns metadata

        val node = MetadataEventToMetadataNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))

        assertSame(metadata, out?.value)
        coVerify(exactly = 1) { metadataService.getById(id) }
    }

    @Test
    fun `a non-uuid object input fails decoding`() = runTest {
        // The generated deserialize bridges through UUIDSerializer, so a JSON object cannot decode.
        val node = MetadataEventToMetadataNode(id = "node-77")
        assertFails {
            node.executeForTest(context, inputs(PipelineValue.ofJson(buildJsonObject { put("k", "v") })))
        }
        coVerify(exactly = 0) { metadataService.getById(any<Uuid>()) }
    }

    @Test
    fun `a non-uuid string input fails parsing`() = runTest {
        val node = MetadataEventToMetadataNode(id = "n1", name = "Resolve Metadata")
        assertFailsWith<IllegalArgumentException> {
            node.executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive("not-a-uuid"))))
        }
    }

    @Test
    fun `a missing input fails with the generated required-input message`() = runTest {
        val node = MetadataEventToMetadataNode(id = "n1")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    @Test
    fun `not found after resolve fails with the node id label when name is blank`() = runTest {
        // Valid UUID but the service has no such metadata → the not-found error fires with the
        // node-id label (blank name) and the id quoted in the message.
        val id = Uuid.random()
        coEvery { metadataService.getById(id) } returns null

        val node = MetadataEventToMetadataNode(id = "node-99")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))
        }
        assertTrue(e.message?.contains("node-99") == true)
        assertTrue(e.message?.contains(id.toString()) == true)
    }

    @Test
    fun `dry run re-runs execute and produces the resolved metadata`() = runTest {
        // TransformNode.dryRun delegates to execute, so a dry run resolves the metadata for real and
        // the trace records the node output (there is no side effect to skip).
        val id = Uuid.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns metadata
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val node = MetadataEventToMetadataNode(id = "n1")
        val out = node.executeForTest(dry, inputs(PipelineValue.of(id, UUIDSerializer())))

        assertSame(metadata, out?.value)
        coVerify(exactly = 1) { metadataService.getById(id) }
    }

    // ── @Serializable round-trip: constructor defaults + node settings shape ──────────────────────

    @Test
    fun `node round-trips through its serializer preserving id name description and position`() {
        val node = MetadataEventToMetadataNode(
            id = "n1",
            name = "Get Metadata",
            description = "loads it",
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(MetadataEventToMetadataNode.serializer(), node)
        val decoded = json.decodeFromString(MetadataEventToMetadataNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node applies its constructor defaults when only the id is supplied`() {
        val node = MetadataEventToMetadataNode(id = "bare")

        assertEquals("bare", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
        assertNull(node.retry)
        assertNull(node.timeoutSeconds)
    }

    @Test
    fun `decoding a minimal graph fragment fills every default`() {
        val decoded = json.decodeFromString(
            MetadataEventToMetadataNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
    }

    @Test
    fun `decoding a graph fragment with an explicit position honors the coordinates`() {
        val decoded = json.decodeFromString(
            MetadataEventToMetadataNode.serializer(),
            """{"id":"n1","name":"Get Metadata","description":"d","position":{"x":5.0,"y":6.0}}""",
        )

        assertEquals("n1", decoded.id)
        assertEquals("Get Metadata", decoded.name)
        assertEquals("d", decoded.description)
        assertEquals(NodePosition(5.0, 6.0), decoded.position)
    }
}
