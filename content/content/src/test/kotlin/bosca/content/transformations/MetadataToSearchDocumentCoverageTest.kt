package bosca.content.transformations

import bosca.category.model.Category
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
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
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Branch/line coverage for [MetadataToSearchDocument]. The exemplar [MetadataToSearchDocumentTest]
 * only exercises the Admin-Search-Index + JSONata path; this suite covers the exclusion filters,
 * both [MetadataToSearchDocument.extractText] arms (text/ vs transform, plus both failure catches),
 * the relationship grouping in [MetadataToSearchDocument.toContext], the null-attributes / null-slug
 * elvis fallbacks, and the blank / null / present JSONata expression arms.
 */
class MetadataToSearchDocumentCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val documentToText = mockk<Transformation<IndexStorageSystem, Metadata, String>>()
    private val storage = mockk<ObjectStorageService>()
    private val slugs = mockk<SlugService>()
    private val json = Json
    private val collections = mockk<MetadataToSearchCollections>()
    private val attributes = mockk<MetadataToSearchAttributes>()
    private val documentService = mockk<bosca.content.metadata.service.DocumentService>()
    private val guideService = mockk<bosca.content.metadata.service.GuideService>()
    private val dataService = mockk<bosca.content.metadata.service.DataService>()
    private val documentReferences = mockk<DocumentReferencesToListTransformation>()
    private val referencesToBookList = mockk<ReferencesListToBookListTransformation>()
    private val configurationService = mockk<ConfigurationService>()

    private val metadataId = UUID.random()
    private val searchConfigId = UUID.random()

    private fun configuration(
        excludeTypes: Set<String> = emptySet(),
        excludeContentTypePrefix: List<String> = emptyList()
    ) = MetadataToSearchDocumentConfiguration(
        collections = collections,
        documentService = documentService,
        guideService = guideService,
        dataService = dataService,
        attributes = attributes,
        excludeTypes = excludeTypes,
        excludeContentTypePrefix = excludeContentTypePrefix
    )

    private fun transformer(
        excludeTypes: Set<String> = emptySet(),
        excludeContentTypePrefix: List<String> = emptyList()
    ) = MetadataToSearchDocument(
        metadataService,
        documentToText,
        storage,
        slugs,
        json,
        configuration(excludeTypes, excludeContentTypePrefix),
        documentReferences,
        referencesToBookList,
        configurationService
    )

    private fun metadata(
        contentType: String = "text/plain",
        languageTag: String = "en",
        attributes: JsonElement? = null,
        id: UUID = metadataId
    ) = Metadata(
        id = id,
        name = "Test Metadata",
        type = MetadataType.STANDARD,
        languageTag = languageTag,
        contentType = contentType,
        contentLength = 100,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = true,
        workflowStateId = "published",
        attributes = attributes
    )

    /** Wires up every collaborator that [MetadataToSearchDocument.toContext] touches. */
    private fun stubContext(
        item: Metadata,
        relationships: List<MetadataRelationship> = emptyList(),
        categories: List<Category> = emptyList(),
        attributesResult: JsonObject? = JsonObject(emptyMap()),
        slug: String? = "test-slug",
        textBody: String = "Body Content"
    ) {
        coEvery { metadataService.getRelationships(item.id) } returns relationships
        coEvery { metadataService.getCategories(item.id) } returns categories
        coEvery { collections.toCollections(metadataService, item) } returns emptyMap()
        coEvery { attributes.toAttributes(metadataService, item) } returns attributesResult
        coEvery { documentReferences.transform(any(), item) } returns emptyList()
        coEvery { referencesToBookList.transform(any(), any()) } returns emptyList()
        coEvery { slugs.getMetadataSlug(item.id) } returns slug
        // text/ path reads storage; non-text path runs documentToText.
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(item) } returns path
        coEvery { storage.getInputStream(path) } returns textBody.byteInputStream()
        coEvery { documentToText.transform(any(), item) } returns textBody
    }

    /** Configure the search transform configuration lookup used by transform(). */
    private fun stubSearchConfiguration(expression: String?) {
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(
            SearchTransformConfiguration(expressions = SearchTransformExpressions(metadata = expression))
        )
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `returns encoded context when no jsonata expression configured`() = runTest {
        val item = metadata()
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer().transform(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)
        val obj = result as JsonObject

        assertEquals("Test Metadata", obj["metadata"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("Body Content", obj["content"]?.jsonPrimitive?.content)
        assertEquals("test-slug", obj["slug"]?.jsonPrimitive?.content)
    }

    @Test
    fun `returns encoded context when jsonata expression is blank`() = runTest {
        val item = metadata()
        stubContext(item)
        stubSearchConfiguration(expression = "   ")

        val result = transformer().transform(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `returns encoded context when search configuration is missing`() = runTest {
        val item = metadata()
        stubContext(item)
        coEvery { configurationService.getByKey("search") } returns null

        val result = transformer().transform(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `applies jsonata transformation when expression present`() = runTest {
        val item = metadata()
        stubContext(item)
        stubSearchConfiguration(expression = "{\"new_name\": metadata.name, \"transformed_content\": content}")

        val result = transformer().transform(IndexStorageSystem(UUID.random(), "Admin Search Index"), item) as JsonObject

        assertEquals("Test Metadata", result["new_name"]?.jsonPrimitive?.content)
        assertEquals("Body Content", result["transformed_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `excludes metadata whose attribute type is in excludeTypes on non-admin index`() = runTest {
        val item = metadata(attributes = JsonObject(mapOf("type" to JsonPrimitive("episode"))))

        val result = transformer(excludeTypes = setOf("episode"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertNull(result)
        coVerify(exactly = 0) { metadataService.getRelationships(any()) }
    }

    @Test
    fun `indexes metadata whose attribute type is not in excludeTypes on non-admin index`() = runTest {
        val item = metadata(attributes = JsonObject(mapOf("type" to JsonPrimitive("show"))))
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer(excludeTypes = setOf("episode"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `treats JsonNull type attribute as absent on non-admin index`() = runTest {
        val item = metadata(attributes = JsonObject(mapOf("type" to JsonNull)))
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer(excludeTypes = setOf("episode"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `treats non-JsonObject attributes as absent type on non-admin index`() = runTest {
        val item = metadata(attributes = JsonPrimitive("not-an-object"))
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer(excludeTypes = setOf("episode"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `handles null attributes on non-admin index`() = runTest {
        val item = metadata(attributes = null)
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer(excludeTypes = setOf("episode"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `excludes metadata whose content type matches excluded prefix on non-admin index`() = runTest {
        val item = metadata(contentType = "image/png")

        val result = transformer(excludeContentTypePrefix = listOf("image/"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertNull(result)
    }

    @Test
    fun `indexes metadata whose content type does not match excluded prefix on non-admin index`() = runTest {
        val item = metadata(contentType = "text/plain")
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer(excludeContentTypePrefix = listOf("image/"))
            .transform(IndexStorageSystem(UUID.random(), "Public Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `admin index skips all exclusion filters`() = runTest {
        // type is in excludeTypes and content type matches an excluded prefix, but Admin index bypasses.
        val item = metadata(
            contentType = "image/png",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("episode")))
        )
        stubContext(item)
        stubSearchConfiguration(expression = null)

        val result = transformer(
            excludeTypes = setOf("episode"),
            excludeContentTypePrefix = listOf("image/")
        ).transform(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertTrue(result is JsonObject)
    }

    @Test
    fun `extractText reads storage bytes for text content`() = runTest {
        val item = metadata(contentType = "text/markdown")
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(item) } returns path
        coEvery { storage.getInputStream(path) } returns "Hello from storage".byteInputStream()

        val text = transformer().extractText(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("Hello from storage", text)
    }

    @Test
    fun `extractText returns empty string when storage read throws`() = runTest {
        val item = metadata(contentType = "text/plain")
        val path = mockk<ObjectPath>()
        coEvery { storage.getPath(item) } returns path
        coEvery { storage.getInputStream(path) } throws IOException("boom")

        val text = transformer().extractText(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("", text)
    }

    @Test
    fun `extractText uses document-to-text transform for non-text content`() = runTest {
        val item = metadata(contentType = "application/pdf")
        coEvery { documentToText.transform(any(), item) } returns "Extracted PDF text"

        val text = transformer().extractText(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("Extracted PDF text", text)
    }

    @Test
    fun `extractText returns empty string when transform returns null`() = runTest {
        val item = metadata(contentType = "application/pdf")
        coEvery { documentToText.transform(any(), item) } returns ""

        val text = transformer().extractText(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("", text)
    }

    @Test
    fun `extractText returns empty string when transform throws`() = runTest {
        val item = metadata(contentType = "application/octet-stream")
        coEvery { documentToText.transform(any(), item) } throws RuntimeException("transform failed")

        val text = transformer().extractText(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("", text)
    }

    @Test
    fun `toContext groups multiple relationships under the same key and preserves distinct keys`() = runTest {
        val item = metadata()
        val relationships = listOf(
            MetadataRelationship(metadataId1 = item.id, metadataId2 = UUID.random(), relationship = "child"),
            MetadataRelationship(metadataId1 = item.id, metadataId2 = UUID.random(), relationship = "child"),
            MetadataRelationship(metadataId1 = item.id, metadataId2 = UUID.random(), relationship = "parent")
        )
        stubContext(item, relationships = relationships)

        val context = transformer().toContext(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals(2, context.relationships["child"]?.size)
        assertEquals(1, context.relationships["parent"]?.size)
    }

    @Test
    fun `toContext falls back to empty attributes when attributes transform returns null`() = runTest {
        val item = metadata()
        stubContext(item, attributesResult = null)

        val context = transformer().toContext(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals(JsonObject(emptyMap()), context.attributes)
    }

    @Test
    fun `toContext uses non-null attributes when attributes transform returns a value`() = runTest {
        val item = metadata()
        val attrs = JsonObject(mapOf("foo" to JsonPrimitive("bar")))
        stubContext(item, attributesResult = attrs)

        val context = transformer().toContext(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("bar", context.attributes["foo"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toContext falls back to empty slug when slug service returns null`() = runTest {
        val item = metadata()
        stubContext(item, slug = null)

        val context = transformer().toContext(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("", context.slug)
    }

    @Test
    fun `toContext carries categories and content through`() = runTest {
        val item = metadata()
        val categoryId = UUID.random()
        stubContext(item, categories = listOf(Category(id = categoryId, name = "News")), textBody = "The body")

        val context = transformer().toContext(IndexStorageSystem(UUID.random(), "Admin Search Index"), item)

        assertEquals("The body", context.content)
        assertEquals(1, context.categories.size)
        assertEquals("News", context.categories.first().name)
    }
}
