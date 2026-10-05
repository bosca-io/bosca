@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.pipeline

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionItem
import bosca.content.collection.pipeline.CollectionEventToCollectionNode
import bosca.content.collection.pipeline.CollectionItemsNode
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.pipeline.MetadataDocumentNode
import bosca.content.metadata.pipeline.MetadataEventToMetadataNode
import bosca.content.metadata.pipeline.MetadataSupplementariesNode
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ContentResolverNodesTest {

    private val metadataService = mockk<MetadataService>()
    private val collectionService = mockk<CollectionService>()
    private val documentService = mockk<DocumentService>()

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MetadataService> { metadataService }
        provides<CollectionService> { collectionService }
        provides<DocumentService> { documentService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    /** A real [Metadata] (not a mock) so the generated deserialize can bridge it through JSON. */
    private fun metadata(id: Uuid, version: Int = 1) = Metadata(
        id = id,
        version = version,
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    @Test
    fun `metadata fromEvent resolves the metadata for a uuid value`() = runTest {
        val id = Uuid.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns metadata

        val node = MetadataEventToMetadataNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))
        assertSame(metadata, out?.value)
    }

    @Test
    fun `metadata fromEvent resolves a bare uuid string`() = runTest {
        val id = Uuid.random()
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(id) } returns metadata

        val node = MetadataEventToMetadataNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive(id.toString()))))
        assertSame(metadata, out?.value)
    }

    @Test
    fun `metadata fromEvent fails clearly when the metadata does not exist`() = runTest {
        val id = Uuid.random()
        coEvery { metadataService.getById(id) } returns null

        val node = MetadataEventToMetadataNode(id = "n1", name = "Resolve metadata")
        val e = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))
        }
        assertEquals(true, e.message?.contains("Resolve metadata"))
    }

    @Test
    fun `collection fromEvent resolves the collection for a uuid value`() = runTest {
        val id = Uuid.random()
        val collection = mockk<Collection>()
        coEvery { collectionService.getById(id) } returns collection

        val node = CollectionEventToCollectionNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.of(id, UUIDSerializer())))
        assertSame(collection, out?.value)
    }

    @Test
    fun `metadata document loads at the metadata object's version`() = runTest {
        val id = Uuid.random()
        val document = Document(metadataId = id, version = 4, title = "Doc")
        val metadata = metadata(id, version = 4)
        coEvery { documentService.getDocument(id, 4) } returns document

        val node = MetadataDocumentNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.of(metadata, Metadata.serializer())))
        assertSame(document, out?.value)
    }

    @Test
    fun `metadata supplementaries resolves the list`() = runTest {
        val id = Uuid.random()
        val metadata = metadata(id, version = 2)
        val supplementaries = listOf(mockk<MetadataSupplementary>())
        coEvery { metadataService.getSupplementary(id) } returns supplementaries

        val node = MetadataSupplementariesNode(id = "n1")
        val out = node.executeForTest(context, inputs(PipelineValue.of(metadata, Metadata.serializer())))
        assertSame(supplementaries, out?.value)
    }

    @Test
    fun `collection items passes the node settings through to the service`() = runTest {
        val id = Uuid.random()
        val collection = Collection(id = id, name = "Docs", languageTag = "en", workflowStateId = "published")
        val items = listOf(mockk<CollectionItem>())
        coEvery {
            collectionService.getItems(id, "published", 0, 25, "en", listOf("bosca/v-document"), true, true)
        } returns items

        val node = CollectionItemsNode(
            id = "n1",
            state = "published",
            limit = 25,
            contentTypes = listOf("bosca/v-document"),
            languageTag = "en",
        )
        val out = node.executeForTest(context, inputs(PipelineValue.of(collection, Collection.serializer())))
        assertSame(items, out?.value)
        coVerify(exactly = 1) {
            collectionService.getItems(id, "published", 0, 25, "en", listOf("bosca/v-document"), true, true)
        }
    }
}
