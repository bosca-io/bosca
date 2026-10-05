package bosca.content.transformations

import bosca.category.service.CategoryService
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CollectionToSearchDocumentTest {

    private val collectionService = mockk<CollectionService>()
    private val slugService = mockk<SlugService>()
    private val categoryService = mockk<CategoryService>()
    private val configurationService = mockk<ConfigurationService>()
    private val json = Json

    private val configuration = CollectionToSearchDocumentConfiguration()

    private val transformer = CollectionToSearchDocument(
        collectionService,
        slugService,
        json,
        configuration,
        categoryService,
        configurationService
    )

    private val collectionId = UUID.random()

    private val collection = Collection(
        id = collectionId,
        name = "Test Collection",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "published",
        public = true,
        attributes = JsonObject(
            mapOf(
                "published" to JsonPrimitive(System.currentTimeMillis()),
                "advertised" to JsonPrimitive(System.currentTimeMillis())
            )
        ),
    )

    private fun createVariant(
        languageTag: String,
        workflowStateId: String
    ) = CollectionLanguageVariant(
        id = collectionId,
        languageTag = languageTag,
        name = "Variant $languageTag",
        workflowStateId = workflowStateId,
    )

    private val searchConfigId = UUID.random()

    private fun setupMocks(variants: List<CollectionLanguageVariant>) {
        coEvery { collectionService.getMetadataRelationships(collectionId) } returns emptyList()
        coEvery { collectionService.getCategoryIds(collectionId) } returns emptyList()
        coEvery { collectionService.getCollectionParents(collectionId) } returns emptyList()
        coEvery { categoryService.getAll() } returns emptyList()
        coEvery { collectionService.getLanguageVariants(collectionId) } returns variants
        coEvery { slugService.getCollectionSlug(collectionId, any()) } returns null
        coEvery { slugService.getCollectionSlug(collectionId) } returns null
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(
            SearchTransformConfiguration(
                expressions = SearchTransformExpressions(
                    collection = """
                    variants[variant = null or (${'$'}${'$'}.isAdmin or variant.workflowStateId = 'published' or variant.workflowStateId = 'advertised')].(${'$'}merge([
                      {
                        "id": ${'$'}string(${'$'}${'$'}.collection.id) & (variant.languageTag ? "-" & variant.languageTag : ""),
                        "contentId": ${'$'}string(${'$'}${'$'}.collection.id),
                        "slug": ${'$'}${'$'}.slug,
                        "languageTag": variant.languageTag ? variant.languageTag : ${'$'}${'$'}.collection.languageTag,
                        "name": variant.name ? variant.name : ${'$'}${'$'}.collection.name,
                        "description": variant.description ? variant.description : (${'$'}${'$'}.collection.description ? ${'$'}${'$'}.collection.description : ${'$'}${'$'}.collection.attributes.description),
                        "labels": ${'$'}${'$'}.collection.labels,
                        "_type": "collection",
                        "contentType": "bosca/v-collection",
                        "published": attributes.published ? attributes.published : ${'$'}toMillis(${'$'}${'$'}.collection.created),
                        "created": ${'$'}toMillis(${'$'}${'$'}.collection.created) / 1000,
                        "modified": ${'$'}toMillis(${'$'}${'$'}.collection.modified) / 1000,
                        "categories": ${'$'}${'$'}.categories.{"id": ${'$'}string(id), "name": name},
                        "content": ""
                      },
                      attributes,
                      collections
                    ]))
                """.trimIndent(),
                )
            )
        )
    }

    @Test
    fun `applies jsonata transformation if configured`() = runTest {
        setupMocks(emptyList())
        coEvery { configurationService.getByKey("search") } returns Configuration(searchConfigId, "search", "", false)
        coEvery { configurationService.getValue(searchConfigId) } returns json.encodeToJsonElement(SearchTransformConfiguration(
            expressions = SearchTransformExpressions(
                collection = "{\"new_name\": collection.name}"
            )
        ))

        val context = IndexStorageSystem(UUID.random(), "Admin Search Index")
        val results = transformer.transform(context, collection)

        assertEquals(1, results.size)
        val doc = results[0] as JsonObject
        assertNotNull(doc["new_name"])
        assertEquals("Test Collection", (doc["new_name"] as JsonPrimitive).content)
    }

    @Test
    fun `non-admin index excludes unpublished variants`() = runTest {
        val publishedVariant = createVariant("es", "published")
        val draftVariant = createVariant("fr", "draft")
        val reviewVariant = createVariant("de", "review")
        setupMocks(listOf(publishedVariant, draftVariant, reviewVariant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Should include collection itself + only the published variant (2 total)
        assertEquals(2, results.size)
    }

    @Test
    fun `non-admin index includes advertised variants`() = runTest {
        val advertisedVariant = createVariant("es", "advertised")
        val draftVariant = createVariant("fr", "draft")
        setupMocks(listOf(advertisedVariant, draftVariant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Should include collection itself + only the advertised variant (2 total)
        assertEquals(2, results.size)
    }

    @Test
    fun `admin index includes all variants regardless of state`() = runTest {
        val publishedVariant = createVariant("es", "published")
        val draftVariant = createVariant("fr", "draft")
        val reviewVariant = createVariant("de", "review")
        setupMocks(listOf(publishedVariant, draftVariant, reviewVariant))

        val context = IndexStorageSystem(UUID.random(), "Admin Search Index")
        val results = transformer.transform(context, collection)

        // Should include collection itself + all 3 variants (4 total)
        assertEquals(4, results.size)
    }

    @Test
    fun `non-admin index with no published variants returns only collection`() = runTest {
        val draftVariant = createVariant("es", "draft")
        val reviewVariant = createVariant("fr", "review")
        setupMocks(listOf(draftVariant, reviewVariant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Should include only the collection itself
        assertEquals(1, results.size)
    }

    @Test
    fun `non-admin index with all published variants includes all`() = runTest {
        val variant1 = createVariant("es", "published")
        val variant2 = createVariant("fr", "published")
        setupMocks(listOf(variant1, variant2))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Should include collection itself + both published variants (3 total)
        assertEquals(3, results.size)
    }

    @Test
    fun `non-admin index excludes pending variants`() = runTest {
        val pendingVariant = createVariant("es", "pending")
        setupMocks(listOf(pendingVariant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Should include only the collection itself
        assertEquals(1, results.size)
    }

    @Test
    fun `admin index includes pending variants`() = runTest {
        val pendingVariant = createVariant("es", "pending")
        setupMocks(listOf(pendingVariant))

        val context = IndexStorageSystem(UUID.random(), "Admin Search Index")
        val results = transformer.transform(context, collection)

        // Should include collection itself + pending variant (2 total)
        assertEquals(2, results.size)
    }

    @Test
    fun `non-admin index mixed published and advertised variants`() = runTest {
        val publishedVariant = createVariant("es", "published")
        val advertisedVariant = createVariant("fr", "advertised")
        val draftVariant = createVariant("de", "draft")
        setupMocks(listOf(publishedVariant, advertisedVariant, draftVariant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Should include collection + published + advertised (3 total, draft excluded)
        assertEquals(3, results.size)
    }

    @Test
    fun `skips SYSTEM collection type`() = runTest {
        val systemCollection = collection.copy(type = CollectionType.SYSTEM)
        setupMocks(emptyList())

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, systemCollection)

        assertEquals(0, results.size)
    }

    @Test
    fun `skips QUEUE collection type`() = runTest {
        val queueCollection = collection.copy(type = CollectionType.QUEUE)
        setupMocks(emptyList())

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, queueCollection)

        assertEquals(0, results.size)
    }

    @Test
    fun `skips ROOT collection type`() = runTest {
        val rootCollection = collection.copy(type = CollectionType.ROOT)
        setupMocks(emptyList())

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, rootCollection)

        assertEquals(0, results.size)
    }

    @Test
    fun `variant search document uses variant name and language`() = runTest {
        val variant = createVariant("es", "published")
        setupMocks(listOf(variant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        // Second result is the variant document
        val variantDoc = results[1] as JsonObject
        assertEquals("Variant es", variantDoc["name"]?.toString()?.trim('"'))
        assertEquals("es", variantDoc["languageTag"]?.toString()?.trim('"'))
    }

    @Test
    fun `variant search document id includes language tag`() = runTest {
        val variant = createVariant("es", "published")
        setupMocks(listOf(variant))

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        val variantDoc = results[1] as JsonObject
        val expectedId = "${collectionId}-es"
        assertEquals(expectedId, variantDoc["id"]?.toString()?.trim('"'))
    }

    @Test
    fun `collection search document id has no language tag suffix`() = runTest {
        setupMocks(emptyList())

        val context = IndexStorageSystem(UUID.random(), "Public Search Index")
        val results = transformer.transform(context, collection)

        val collectionDoc = results[0] as JsonObject
        assertEquals(collectionId.toString(), collectionDoc["id"]?.toString()?.trim('"'))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `production json configuration indexes the base collection`() = runTest {
        setupMocks(emptyList())
        val productionJson = Json(json) {
            explicitNulls = false
        }
        val productionTransformer = CollectionToSearchDocument(
            collectionService,
            slugService,
            productionJson,
            configuration,
            categoryService,
            configurationService
        )

        val results = productionTransformer.transform(
            IndexStorageSystem(UUID.random(), "Public Search Index"),
            collection
        )

        val collectionDoc = assertIs<JsonObject>(results.single())
        assertEquals(collectionId.toString(), collectionDoc["id"]?.toString()?.trim('"'))
    }
}
