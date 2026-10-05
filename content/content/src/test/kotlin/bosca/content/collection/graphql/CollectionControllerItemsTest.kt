package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionItem
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.category.service.CategoryService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.trait.service.TraitService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionControllerItemsTest {

    private val collectionService = mockk<CollectionService>()
    private val categoryService = mockk<CategoryService>()
    private val traitService = mockk<TraitService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val slugService = mockk<SlugService>()
    private val json = Json

    private val controller = CollectionController(
        collectionService,
        categoryService,
        traitService,
        collectionPermissionEvaluator,
        metadataService,
        metadataPermissionEvaluator,
        slugService,
        json
    )

    private val authentication = mockk<AuthenticationContext>()

    @BeforeTest
    fun setUp() {
        coEvery { collectionService.getLanguageVariant(any(), any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createCollection(
        id: UUID = UUID.random(),
        languageTag: String = "en",
        workflowStateId: String = "published"
    ) = Collection(
        id = id,
        name = "Test Collection",
        languageTag = languageTag,
        type = CollectionType.STANDARD,
        workflowStateId = workflowStateId
    )

    @Test
    fun `items passes languageTag to service`() = runTest {
        val collection = createCollection()
        val languageTag = "es"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, languageTag, null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, null, languageTag, null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, null, 0L, 10, languageTag, null) }
    }

    @Test
    fun `items passes language resolution context to service`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery {
            collectionService.getItems(
                collection.id,
                null,
                0L,
                10,
                "en",
                null,
                languageResolutionContext = "bibles",
            )
        } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, null, "en", null, "bibles")

        assertEquals(emptyList(), result)
        coVerify {
            collectionService.getItems(
                collection.id,
                null,
                0L,
                10,
                "en",
                null,
                languageResolutionContext = "bibles",
            )
        }
    }

    @Test
    fun `items passes null languageTag when not specified`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, null, null, null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, null, 0L, 10, null, null) }
    }

    @Test
    fun `items passes languageTag with state filter`() = runTest {
        val collection = createCollection()
        val languageTag = "fr"
        val state = "published"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, state, 0L, 10, languageTag, null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, state, languageTag, null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, state, 0L, 10, languageTag, null) }
    }

    @Test
    fun `itemsCount passes languageTag to service`() = runTest {
        val collection = createCollection()
        val languageTag = "es"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItemsCount(collection.id, null, languageTag, null) } returns 5L

        val result = controller.itemsCount(authentication, collection, null, languageTag, null)

        assertEquals(5L, result)
        coVerify { collectionService.getItemsCount(collection.id, null, languageTag, null) }
    }

    @Test
    fun `itemsCount passes language resolution context to service`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery {
            collectionService.getItemsCount(
                collection.id,
                null,
                "en",
                null,
                languageResolutionContext = "bibles",
            )
        } returns 1L

        val result = controller.itemsCount(authentication, collection, null, "en", null, "bibles")

        assertEquals(1L, result)
        coVerify {
            collectionService.getItemsCount(
                collection.id,
                null,
                "en",
                null,
                languageResolutionContext = "bibles",
            )
        }
    }

    @Test
    fun `itemsCount returns 0 when no LIST permission`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns false

        val result = controller.itemsCount(authentication, collection, null, "es", null)

        assertEquals(0L, result)
        coVerify(exactly = 0) { collectionService.getItemsCount(any(), any(), any(), any()) }
    }

    @Test
    fun `collections passes languageTag with includeMetadata false`() = runTest {
        val collection = createCollection()
        val languageTag = "de"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, languageTag, null, includeMetadata = false) } returns emptyList()

        val result = controller.collections(authentication, collection, 0L, 10, null, languageTag)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, null, 0L, 10, languageTag, null, includeMetadata = false) }
    }

    @Test
    fun `collectionsCount passes languageTag to service`() = runTest {
        val collection = createCollection()
        val languageTag = "de"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItemsCount(collection.id, null, languageTag, null, includeMetadata = false) } returns 3L

        val result = controller.collectionsCount(authentication, collection, null, languageTag)

        assertEquals(3L, result)
        coVerify { collectionService.getItemsCount(collection.id, null, languageTag, null, includeMetadata = false) }
    }

    @Test
    fun `metadata passes languageTag with includeCollections false`() = runTest {
        val collection = createCollection()
        val languageTag = "ja"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, languageTag, null, includeCollections = false) } returns emptyList()

        val result = controller.metadata(authentication, collection, 0L, 10, null, languageTag, null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, null, 0L, 10, languageTag, null, includeCollections = false) }
    }

    @Test
    fun `metadataCount passes languageTag to service`() = runTest {
        val collection = createCollection()
        val languageTag = "ja"
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItemsCount(collection.id, null, languageTag, null, includeCollections = false) } returns 7L

        val result = controller.metadataCount(authentication, collection, null, languageTag, null)

        assertEquals(7L, result)
        coVerify { collectionService.getItemsCount(collection.id, null, languageTag, null, includeCollections = false) }
    }

    @Test
    fun `items with languageTag and contentTypes passes both to service`() = runTest {
        val collection = createCollection()
        val languageTag = "es"
        val contentTypes = listOf("video/mp4", "audio/mpeg")
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, languageTag, contentTypes) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, null, languageTag, contentTypes)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, null, 0L, 10, languageTag, contentTypes) }
    }

    @Test
    fun `items with state and languageTag passes both to service`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, "published", 0L, 10, "es", null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, "published", "es", null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, "published", 0L, 10, "es", null) }
    }

    @Test
    fun `items returns empty when no LIST permission`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns false
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, null, null, null)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `collectionsCount returns 0 when no LIST permission`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns false

        val result = controller.collectionsCount(authentication, collection, null, "es")

        assertEquals(0L, result)
        coVerify(exactly = 0) { collectionService.getItemsCount(any(), any(), any(), any(), includeMetadata = any()) }
    }

    @Test
    fun `metadataCount returns 0 when no LIST permission`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns false

        val result = controller.metadataCount(authentication, collection, null, "es", null)

        assertEquals(0L, result)
        coVerify(exactly = 0) { collectionService.getItemsCount(any(), any(), any(), any(), includeCollections = any()) }
    }

    @Test
    fun `items with advertised state and languageTag`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, "advertised", 0L, 20, "fr", null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 20, "advertised", "fr", null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, "advertised", 0L, 20, "fr", null) }
    }

    @Test
    fun `items with draft state passes state through`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, "draft", 0L, 10, null, null) } returns emptyList()

        val result = controller.items(authentication, collection, 0L, 10, "draft", null, null)

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, "draft", 0L, 10, null, null) }
    }

    @Test
    fun `itemsCount with state and languageTag passes both`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItemsCount(collection.id, "published", "es", null) } returns 12L

        val result = controller.itemsCount(authentication, collection, "published", "es", null)

        assertEquals(12L, result)
        coVerify { collectionService.getItemsCount(collection.id, "published", "es", null) }
    }

    @Test
    fun `metadata with state and languageTag passes both`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, "published", 0L, 10, "de", listOf("video/mp4"), includeCollections = false) } returns emptyList()

        val result = controller.metadata(authentication, collection, 0L, 10, "published", "de", listOf("video/mp4"))

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, "published", 0L, 10, "de", listOf("video/mp4"), includeCollections = false) }
    }

    @Test
    fun `collections with state and languageTag passes both`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItems(collection.id, "published", 0L, 10, "ja", null, includeMetadata = false) } returns emptyList()

        val result = controller.collections(authentication, collection, 0L, 10, "published", "ja")

        assertEquals(emptyList(), result)
        coVerify { collectionService.getItems(collection.id, "published", 0L, 10, "ja", null, includeMetadata = false) }
    }

    @Test
    fun `metadataCount with contentTypes passes all params`() = runTest {
        val collection = createCollection()
        coEvery { collectionPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Collection>(), PermissionAction.LIST) } returns true
        coEvery { collectionService.getItemsCount(collection.id, "published", "es", listOf("text/plain"), includeCollections = false) } returns 3L

        val result = controller.metadataCount(authentication, collection, "published", "es", listOf("text/plain"))

        assertEquals(3L, result)
    }
}
