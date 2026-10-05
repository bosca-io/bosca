@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.service.MetadataService
import bosca.content.pipeline.executeForTest
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
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
 * Focused coverage for [MetadataSupplementariesNode]: the paths the shared `ContentResolverNodesTest`
 * leaves untouched — the non-Metadata rejection (`error(...)`), both `name.ifBlank { id }` label
 * branches on that rejection, the empty-list output path, and a `@Serializable` round-trip that
 * exercises the constructor defaults and settings shape (the generated serializer branches).
 *
 * The happy resolve-the-list path is already covered there; this only fills the remaining branches.
 */
class MetadataSupplementariesNodeCoverageTest {

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

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

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

    // ── execute: happy paths ──────────────────────────────────────────────────────────────────────

    @Test
    fun `execute resolves the supplementaries for the inbound metadata`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        val supplementaries = listOf(mockk<MetadataSupplementary>(), mockk<MetadataSupplementary>())
        coEvery { metadataService.getSupplementary(id) } returns supplementaries

        val out = MetadataSupplementariesNode(id = "n1")
            .executeForTest(context, inputs(PipelineValue.of(metadata, Metadata.serializer())))

        assertSame(supplementaries, out?.value)
        coVerify(exactly = 1) { metadataService.getSupplementary(id) }
    }

    @Test
    fun `execute passes through an empty supplementaries list`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id)
        coEvery { metadataService.getSupplementary(id) } returns emptyList()

        val out = MetadataSupplementariesNode(id = "n1", name = "Get Supps")
            .executeForTest(context, inputs(PipelineValue.of(metadata, Metadata.serializer())))

        assertEquals(emptyList<MetadataSupplementary>(), out?.value)
    }

    // ── execute: non-Metadata rejection + both label branches ─────────────────────────────────────

    @Test
    fun `execute rejects a non-Metadata in value`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        assertFailsWith<SerializationException> {
            MetadataSupplementariesNode(id = "n1", name = "Resolve Supps")
                .executeForTest(context, inputs(PipelineValue.of(Uuid.random(), UUIDSerializer())))
        }
        coVerify(exactly = 0) { metadataService.getSupplementary(any<Uuid>()) }
    }

    @Test
    fun `execute rejects a bare JSON in value that is not a Metadata`() = runTest {
        assertFailsWith<SerializationException> {
            MetadataSupplementariesNode(id = "n1")
                .executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive("not a metadata"))))
        }
    }

    @Test
    fun `execute rejects a missing in value with the generated required-input message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            MetadataSupplementariesNode(id = "n1")
                .executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── @Serializable round-trip: constructor defaults + settings shape ───────────────────────────

    @Test
    fun `node round-trips through its serializer preserving id, name, description, and position`() {
        val node = MetadataSupplementariesNode(
            id = "n1",
            name = "Supp Getter",
            description = "loads supplementaries",
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(MetadataSupplementariesNode.serializer(), node)
        val decoded = json.decodeFromString(MetadataSupplementariesNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node applies its constructor defaults when only the id is supplied`() {
        val node = MetadataSupplementariesNode(id = "bare")

        assertEquals("bare", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `decoding a minimal graph fragment fills every default`() {
        val decoded = json.decodeFromString(
            MetadataSupplementariesNode.serializer(),
            """{"id":"only-id"}""",
        )

        assertEquals("only-id", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertEquals(NodePosition(), decoded.position)
        assertNull(decoded.retry)
        assertNull(decoded.timeoutSeconds)
    }
}
