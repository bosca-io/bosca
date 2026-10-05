package bosca.content.transformations

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.transformations.Transformation
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MetadataToSearchDocumentTest {

    private val metadataService = mockk<MetadataService>()
    private val documentToText = mockk<Transformation<IndexStorageSystem, Metadata, String>>()
    private val storage = mockk<ObjectStorageService>()
    private val slugs = mockk<SlugService>()
    private val json = Json
    private val documentService = mockk<DocumentService>()
    private val dataService = mockk<DataService>()
    private val guideService = mockk<GuideService>()
    private val collectionsService = mockk<CollectionService>()
    private val configuration = MetadataToSearchDocumentConfiguration(
        collections = DefaultMetadataToSearchCollections(collectionsService),
        documentService = documentService,
        guideService = guideService,
        dataService = dataService
    )
    private val documentReferences = mockk<DocumentReferencesToListTransformation>()
    private val referencesToBookList = mockk<ReferencesListToBookListTransformation>()
    private val configurationService = mockk<ConfigurationService>()

    private val transformer = MetadataToSearchDocument(
        metadataService,
        documentToText,
        storage,
        slugs,
        json,
        configuration,
        documentReferences,
        referencesToBookList,
        configurationService
    )

    private val metadataId = UUID.random()
    private val searchConfigId = UUID.random()

    private val metadata = Metadata(
        id = metadataId,
        name = "Test Metadata",
        type = MetadataType.STANDARD,
        languageTag = "en",
        contentType = "text/plain",
        contentLength = 100,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = true,
        workflowStateId = "published"
    )

    @Test
    fun `applies jsonata transformation if configured`() = runTest {
        val path = mockk<ObjectPath>()
        coEvery { metadataService.getRelationships(metadataId) } returns emptyList()
        coEvery { metadataService.getCategories(metadataId) } returns emptyList()
        coEvery { metadataService.getParents(metadataId) } returns emptyList()
        coEvery { documentReferences.transform(any(), any()) } returns emptyList()
        coEvery { referencesToBookList.transform(any(), any()) } returns emptyList()
        coEvery { documentService.getDocument(any(), any()) } returns null
        coEvery { guideService.getGuide(any(), any()) } returns null
        coEvery { dataService.getData(any(), any()) } returns null
        coEvery { slugs.getMetadataSlug(metadataId) } returns "test-slug"
        coEvery { storage.getPath(metadata) } returns path
        coEvery { storage.getInputStream(path) } returns "Test Content".byteInputStream()
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(SearchTransformConfiguration(
            expressions = SearchTransformExpressions(
                metadata = "{\"new_name\": metadata.name, \"transformed_content\": content}"
            )
        ))

        val context = IndexStorageSystem(UUID.random(), "Admin Search Index")
        val result = transformer.transform(context, metadata) as JsonObject

        assertNotNull(result["new_name"])
        assertEquals("Test Metadata", (result["new_name"] as JsonPrimitive).content)
        assertEquals("Test Content", (result["transformed_content"] as JsonPrimitive).content)
    }
}
