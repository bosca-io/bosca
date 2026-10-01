@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DocumentService
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
import kotlinx.serialization.json.JsonNull
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
 * Focused coverage for [MetadataDocumentNode] — the branches the shared `ContentResolverNodesTest`
 * leaves untouched: the non-Metadata `in`-port rejection (`error(...)`, with both the non-blank-name
 * and blank-name → node-id label branches), the missing-`in`-port rejection through the same guard,
 * the not-found `JsonNull` output arm when [DocumentService.getDocument] returns null, and a
 * `@Serializable` round-trip that exercises the generated serializer's write/read branches and the
 * constructor defaults.
 *
 * The happy path (a real document loaded at the metadata's version) is already covered there; this
 * only fills the remaining lines and branches.
 */
class MetadataDocumentNodeCoverageTest {

    private val documentService = mockk<DocumentService>()

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }

    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<DocumentService> { documentService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    /** A real [Metadata] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun metadata(id: Uuid = Uuid.random(), version: Int = 1) = Metadata(
        id = id,
        version = version,
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    private fun metadataInput(metadata: Metadata) =
        NodeInputs(mapOf("in" to PipelineValue.of(metadata, Metadata.serializer())))

    // ── execute: not-found → JsonNull ─────────────────────────────────────────────────────────────

    @Test
    fun `execute returns a JSON null value when the document does not exist`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id, version = 7)
        coEvery { documentService.getDocument(id, 7) } returns null

        val node = MetadataDocumentNode(id = "n1")
        val out = node.executeForTest(context, metadataInput(metadata))

        // The null-document arm wraps JsonNull (no origin serializer), not a Document.
        assertSame(JsonNull, out?.value)
        coVerify(exactly = 1) { documentService.getDocument(id, 7) }
    }

    @Test
    fun `execute loads and wraps the document at the metadata version with the Document serializer`() = runTest {
        val id = Uuid.random()
        val document = Document(metadataId = id, version = 3, title = "Doc")
        val metadata = metadata(id, version = 3)
        coEvery { documentService.getDocument(id, 3) } returns document

        val out = MetadataDocumentNode(id = "n1").executeForTest(context, metadataInput(metadata))

        assertSame(document, out?.value)
        // The output rides the explicit Document serializer for downstream chaining.
        assertEquals(Document.serializer().descriptor.serialName, out?.serializer?.descriptor?.serialName)
    }

    // ── execute: non-Metadata / missing `in` rejection + label branches ───────────────────────────

    @Test
    fun `execute rejects a non-Metadata in port`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        val node = MetadataDocumentNode(id = "the-node", name = "Get Document")
        assertFailsWith<SerializationException> {
            node.executeForTest(
                context,
                NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("not a metadata")))),
            )
        }
    }

    @Test
    fun `execute rejects a missing in port with the generated required-input message`() = runTest {
        val node = MetadataDocumentNode(id = "empty-node")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue(e.message?.contains("required input 'in'") == true)
    }

    // ── @Serializable round-trip: serializer write/read branches + constructor defaults ───────────

    @Test
    fun `node round-trips through its serializer preserving id name description and position`() {
        val node = MetadataDocumentNode(
            id = "n1",
            name = "Document Loader",
            description = "loads the body",
            position = NodePosition(12.0, 34.0),
        )

        val encoded = json.encodeToString(MetadataDocumentNode.serializer(), node)
        val decoded = json.decodeFromString(MetadataDocumentNode.serializer(), encoded)

        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(node.position, decoded.position)
    }

    @Test
    fun `node applies its constructor defaults when only the id is supplied`() {
        val node = MetadataDocumentNode(id = "bare")

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
            MetadataDocumentNode.serializer(),
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
