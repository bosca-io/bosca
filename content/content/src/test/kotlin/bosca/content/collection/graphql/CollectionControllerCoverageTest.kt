package bosca.content.collection.graphql

import bosca.category.model.Category
import bosca.category.service.CategoryService
import bosca.content.attributes.model.AttributesFilterInput
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionFindResult
import bosca.content.collection.model.CollectionItem
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionPermission
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionType
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.Ordering
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.BatchLoaderEnvironment
import bosca.graphql.DataFetchingEnvironment
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.LanguageItem
import bosca.server.Parameters
import bosca.server.RequestCookies
import bosca.slug.service.SlugService
import bosca.trait.model.Trait
import bosca.trait.service.TraitService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionControllerCoverageTest {

    @Test
    fun `collection lists and counts resolve language variants before checking access`() = runTest {
        val collection = createCollection(languageTag = "en")
        val translated = createVariant(languageTag = "es")
        coEvery { collectionService.getLanguageVariant(collection.id, "es") } returns translated
        coEvery { collectionService.getLanguageVariant(collection.id, "pt") } returns null
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST) } returns true
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, translated, PermissionAction.LIST) } returns true
        for (language in listOf("EN", "es", "pt")) {
            val resolved = if (language == "es") translated else collection
            coEvery { collectionService.getItems(collection.id, null, 0L, 10, language, null, includeMetadata = false) } returns emptyList()
            coEvery { collectionService.getItemsCount(resolved.id, null, language, null, includeMetadata = false) } returns 2L
            coEvery { collectionService.getItemsCount(resolved.id, null, language, null, includeCollections = false) } returns 3L
            assertEquals(emptyList(), controller.collections(authentication, collection, 0L, 10, null, language))
            assertEquals(2L, controller.collectionsCount(authentication, collection, null, language))
            assertEquals(3L, controller.metadataCount(authentication, collection, null, language, null))
        }
        coEvery { collectionPermissionEvaluator.isAllowed(authentication, translated, PermissionAction.LIST) } returns false
        assertEquals(0L, controller.collectionsCount(authentication, collection, null, "es"))
        assertEquals(0L, controller.metadataCount(authentication, collection, null, "es", null))
    }

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

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun createCollection(
        id: UUID = UUID.random(),
        languageTag: String = "en",
        workflowStateId: String = "published",
        attributes: JsonObject? = null,
        systemAttributes: JsonObject? = null,
        labels: List<String> = emptyList(),
        ordering: kotlinx.serialization.json.JsonElement? = null,
        etag: String? = "etag-1",
        description: String? = "desc",
        templateMetadataId: UUID? = null,
        templateMetadataVersion: Int? = null,
        locked: Boolean = false,
        itemsLocked: Boolean = false,
        public: Boolean = false,
        publicList: Boolean = false,
        publicSupplementary: Boolean = false,
        searchable: Boolean = true,
        deleted: Boolean = false,
        ready: OffsetDateTime? = null
    ) = Collection(
        id = id,
        name = "Test Collection",
        languageTag = languageTag,
        type = CollectionType.STANDARD,
        description = description,
        attributes = attributes,
        systemAttributes = systemAttributes,
        labels = labels,
        ready = ready,
        etag = etag,
        ordering = ordering,
        workflowStateId = workflowStateId,
        public = public,
        publicList = publicList,
        publicSupplementary = publicSupplementary,
        locked = locked,
        itemsLocked = itemsLocked,
        deleted = deleted,
        templateMetadataId = templateMetadataId,
        templateMetadataVersion = templateMetadataVersion,
        searchable = searchable
    )

    private fun createVariant(
        id: UUID = UUID.random(),
        languageTag: String = "es",
        workflowStateId: String = "published"
    ) = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = "Variant",
        workflowStateId = workflowStateId
    )

    private fun createMetadata(
        id: UUID = UUID.random(),
        workflowStateId: String = "published"
    ) = Metadata(
        id = id,
        name = "Meta",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = workflowStateId
    )

    // ---- Simple scalar field accessors ----

    @Test
    fun `simple field accessors return backing values`() {
        val id = UUID.random()
        val now = OffsetDateTime.now()
        val collection = createCollection(
            id = id,
            labels = listOf("a", "b"),
            ready = now
        )

        assertEquals(id, controller.id(collection))
        assertEquals("Test Collection", controller.name(collection))
        assertEquals("en", controller.languageTag(collection))
        assertEquals("etag-1", controller.etag(collection, true))
        assertEquals("etag-1", controller.etag(collection, false))
        assertEquals(listOf("a", "b"), controller.labels(collection))
        assertEquals("desc", controller.description(collection))
        assertEquals(CollectionType.STANDARD, controller.type(collection))
        assertEquals(collection.systemAttributes, controller.systemAttributes(collection))
        assertEquals(collection.itemAttributes, controller.itemAttributes(collection))
        assertEquals(false, controller.locked(collection))
        assertEquals(false, controller.itemsLocked(collection))
        assertEquals(false, controller.public(collection))
        assertEquals(false, controller.publicList(collection))
        assertEquals(false, controller.publicSupplementary(collection))
        assertEquals(true, controller.searchable(collection))
        assertEquals(true, controller.recommendable(collection))
        assertEquals(false, controller.deleted(collection))
        assertEquals(collection.created, controller.created(collection))
        assertEquals(collection.modified, controller.modified(collection))
        assertEquals(now, controller.ready(collection))
    }

    // ---- ordering ----

    @Test
    fun `ordering returns empty list when ordering json is null`() {
        val collection = createCollection(ordering = null)
        assertEquals(emptyList(), controller.ordering(collection))
    }

    @Test
    fun `ordering decodes json array into orderings`() {
        val orderingJson = JsonArray(
            listOf(JsonObject(mapOf("field" to JsonPrimitive("name"))))
        )
        val collection = createCollection(ordering = orderingJson)
        val result: List<Ordering> = controller.ordering(collection)
        assertEquals(1, result.size)
        assertEquals("name", result[0].field)
    }

    // ---- traits / traitIds / categories ----

    @Test
    fun `traits returns filtered traits from service`() = runTest {
        val collection = createCollection()
        coEvery { collectionService.getTraitIds(collection.id) } returns listOf("t1")
        coEvery { traitService.getAll() } returns listOf(
            Trait("t1", "T1", "d", null),
            Trait("t2", "T2", "d", null)
        )

        val result = controller.traits(collection)
        assertEquals(listOf("t1"), result.map { it.id })
    }

    @Test
    fun `traitIds returns ids of filtered traits`() = runTest {
        val collection = createCollection()
        coEvery { collectionService.getTraitIds(collection.id) } returns listOf("t2")
        coEvery { traitService.getAll() } returns listOf(
            Trait("t1", "T1", "d", null),
            Trait("t2", "T2", "d", null)
        )

        assertEquals(listOf("t2"), controller.traitIds(collection))
    }

    @Test
    fun `categories returns filtered categories from service`() = runTest {
        val collection = createCollection()
        val catId = UUID.random()
        coEvery { collectionService.getCategoryIds(collection.id) } returns listOf(catId)
        coEvery { categoryService.getAll() } returns listOf(Category(catId, "C1"))

        val result = controller.categories(collection)
        assertEquals(listOf(catId), result.map { it.id })
    }

    @Test
    fun `categories returns empty when no category ids`() = runTest {
        val collection = createCollection()
        coEvery { collectionService.getCategoryIds(collection.id) } returns emptyList()

        assertEquals(emptyList(), controller.categories(collection))
    }

    // ---- slug ----

    @Test
    fun `slug delegates to slug service`() = runTest {
        val batch = Batch<CollectionCacheKeyId, String>(listOf(CollectionCacheKeyId(UUID.random())))
        coEvery { slugService.addCollectionSlugsToBatch(batch) } just Runs

        controller.slug(batch)

        assertNull(batch.getData(batch.keys.first()))
    }

    // ---- attributes ----

    @Test
    fun `attributes returns raw attributes when filter is null and not advertised`() = runTest {
        val attrs = JsonObject(mapOf("type" to JsonPrimitive("thing")))
        val collection = createCollection(attributes = attrs)

        val result = controller.attributes(authentication, collection, null)
        assertEquals(attrs, result)
    }

    @Test
    fun `attributes filters when filter provided and attributes is a map`() = runTest {
        val attrs = JsonObject(
            mapOf(
                "type" to JsonPrimitive("thing"),
                "secret" to JsonPrimitive("hidden")
            )
        )
        val collection = createCollection(attributes = attrs)
        val filter = AttributesFilterInput(attributes = listOf("type"))

        val result = controller.attributes(authentication, collection, filter)
        assertEquals(mapOf("type" to JsonPrimitive("thing")), result)
    }

    @Test
    fun `attributes returns empty map when filter provided but attributes not a map`() = runTest {
        val collection = createCollection(attributes = null)
        val filter = AttributesFilterInput(attributes = listOf("type"))

        val result = controller.attributes(authentication, collection, filter)
        assertEquals(emptyMap<String, Any>(), result)
    }

    @Test
    fun `attributes applies restricted filter when advertised and not allowed to edit`() = runTest {
        val attrs = JsonObject(
            mapOf(
                "type" to JsonPrimitive("thing"),
                "description" to JsonPrimitive("d"),
                "published" to JsonPrimitive("yes"),
                "secret" to JsonPrimitive("hidden")
            )
        )
        val collection = createCollection(workflowStateId = "advertised", attributes = attrs)
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.EDIT)
        } returns false

        val result = controller.attributes(authentication, collection, null) as Map<*, *>
        assertTrue(result.containsKey("type"))
        assertTrue(result.containsKey("description"))
        assertTrue(result.containsKey("published"))
        assertTrue(!result.containsKey("secret"))
    }

    @Test
    fun `attributes returns raw attributes when advertised but allowed to edit`() = runTest {
        val attrs = JsonObject(mapOf("secret" to JsonPrimitive("hidden")))
        val collection = createCollection(workflowStateId = "advertised", attributes = attrs)
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.EDIT)
        } returns true

        val result = controller.attributes(authentication, collection, null)
        assertEquals(attrs, result)
    }

    // ---- supplementary ----

    @Test
    fun `supplementary returns empty when not allowed`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.isSupplementaryAllowed(authentication, collection, PermissionAction.VIEW)
        } returns false

        assertEquals(emptyList(), controller.supplementary(authentication, collection, null, null))
    }

    private fun createSupplementary(
        key: String = "k",
        planId: UUID? = null
    ) = CollectionSupplementary(
        id = UUID.random(),
        collectionId = UUID.random(),
        key = key,
        name = "n",
        planId = planId,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now()
    )

    @Test
    fun `supplementary filters by key when key provided`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.isSupplementaryAllowed(authentication, collection, PermissionAction.VIEW)
        } returns true
        coEvery { collectionService.getSupplementary(collection.id) } returns listOf(
            createSupplementary(key = "wanted"),
            createSupplementary(key = "other")
        )

        val result = controller.supplementary(authentication, collection, "wanted", null)
        assertEquals(1, result.size)
        assertEquals("wanted", result.first().supplementary.key)
    }

    @Test
    fun `supplementary filters by planId when key null and planId provided`() = runTest {
        val collection = createCollection()
        val planId = UUID.random()
        coEvery {
            collectionPermissionEvaluator.isSupplementaryAllowed(authentication, collection, PermissionAction.VIEW)
        } returns true
        coEvery { collectionService.getSupplementary(collection.id) } returns listOf(
            createSupplementary(planId = planId),
            createSupplementary(planId = UUID.random())
        )

        val result = controller.supplementary(authentication, collection, null, planId)
        assertEquals(1, result.size)
        assertEquals(planId, result.first().supplementary.planId)
    }

    @Test
    fun `supplementary returns all when key and planId null`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.isSupplementaryAllowed(authentication, collection, PermissionAction.VIEW)
        } returns true
        coEvery { collectionService.getSupplementary(collection.id) } returns listOf(
            createSupplementary(),
            createSupplementary()
        )

        val result = controller.supplementary(authentication, collection, null, null)
        assertEquals(2, result.size)
    }

    // ---- items (drives toList branches) ----

    @Test
    fun `items returns empty when list permission denied`() = runTest {
        val collection = createCollection()
        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns listOf(
            CollectionItem(collectionId = collection.id, childMetadataId = UUID.random())
        )
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<Collection>(), PermissionAction.LIST)
        } returns false

        assertEquals(emptyList(), controller.items(authentication, collection, 0L, 10, null, null, null))
    }

    @Test
    fun `items includes child collection and child metadata that are allowed`() = runTest {
        val collection = createCollection()
        val childCollectionId = UUID.random()
        val childMetadataId = UUID.random()
        val childCollection = createCollection(id = childCollectionId)
        val childMetadata = createMetadata(id = childMetadataId)

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns listOf(
            CollectionItem(collectionId = collection.id, childCollectionId = childCollectionId),
            CollectionItem(collectionId = collection.id, childMetadataId = childMetadataId)
        )
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<Collection>(), PermissionAction.LIST)
        } returns true
        coEvery { collectionService.getById(childCollectionId) } returns childCollection
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, childCollection, PermissionAction.VIEW)
        } returns true
        coEvery { metadataService.getById(childMetadataId) } returns childMetadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, childMetadata, PermissionAction.VIEW)
        } returns true

        val result = controller.items(authentication, collection, 0L, 10, null, null, null)
        assertEquals(2, result.size)
    }

    @Test
    fun `items skips child collection when getById returns null`() = runTest {
        val collection = createCollection()
        val childCollectionId = UUID.random()

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns listOf(
            CollectionItem(collectionId = collection.id, childCollectionId = childCollectionId)
        )
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<Collection>(), PermissionAction.LIST)
        } returns true
        coEvery { collectionService.getById(childCollectionId) } returns null

        assertEquals(emptyList(), controller.items(authentication, collection, 0L, 10, null, null, null))
    }

    @Test
    fun `items skips child collection when view denied`() = runTest {
        val collection = createCollection()
        val childCollectionId = UUID.random()
        val childCollection = createCollection(id = childCollectionId)

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns listOf(
            CollectionItem(collectionId = collection.id, childCollectionId = childCollectionId)
        )
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<Collection>(), PermissionAction.LIST)
        } returns true
        coEvery { collectionService.getById(childCollectionId) } returns childCollection
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, childCollection, PermissionAction.VIEW)
        } returns false

        assertEquals(emptyList(), controller.items(authentication, collection, 0L, 10, null, null, null))
    }

    @Test
    fun `items skips child metadata when getById null and when view denied`() = runTest {
        val collection = createCollection()
        val missingMetadataId = UUID.random()
        val deniedMetadataId = UUID.random()
        val deniedMetadata = createMetadata(id = deniedMetadataId)

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, null, null) } returns listOf(
            CollectionItem(collectionId = collection.id, childMetadataId = missingMetadataId),
            CollectionItem(collectionId = collection.id, childMetadataId = deniedMetadataId)
        )
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<Collection>(), PermissionAction.LIST)
        } returns true
        coEvery { metadataService.getById(missingMetadataId) } returns null
        coEvery { metadataService.getById(deniedMetadataId) } returns deniedMetadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, deniedMetadata, PermissionAction.VIEW)
        } returns false

        assertEquals(emptyList(), controller.items(authentication, collection, 0L, 10, null, null, null))
    }

    @Test
    fun `items resolves language variant when languageTag differs from collection tag`() = runTest {
        val collection = createCollection(languageTag = "en")
        val variant = createVariant(id = collection.id, languageTag = "es")

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, "es", null) } returns emptyList()
        coEvery { collectionService.getLanguageVariant(collection.id, "es") } returns variant
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<bosca.content.collection.model.ICollection>(), PermissionAction.LIST)
        } returns true

        val result = controller.items(authentication, collection, 0L, 10, null, "es", null)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `items uses language variant for child security item`() = runTest {
        val collection = createCollection(languageTag = "en")
        val childCollectionId = UUID.random()
        val childCollection = createCollection(id = childCollectionId, languageTag = "en")
        val childVariant = createVariant(id = childCollectionId, languageTag = "es")

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, "es", null) } returns listOf(
            CollectionItem(collectionId = collection.id, childCollectionId = childCollectionId)
        )
        // collection's own tag is "en" so the collection-language-variant lookup for the parent triggers
        coEvery { collectionService.getLanguageVariant(collection.id, "es") } returns null
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<bosca.content.collection.model.ICollection>(), PermissionAction.LIST)
        } returns true
        coEvery { collectionService.getById(childCollectionId) } returns childCollection
        coEvery { collectionService.getLanguageVariant(childCollectionId, "es") } returns childVariant
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, childVariant, PermissionAction.VIEW)
        } returns true

        val result = controller.items(authentication, collection, 0L, 10, null, "es", null)
        assertEquals(1, result.size)
    }

    @Test
    fun `items skips child collection when language variant security item is null`() = runTest {
        val collection = createCollection(languageTag = "en")
        val childCollectionId = UUID.random()
        val childCollection = createCollection(id = childCollectionId, languageTag = "en")

        coEvery { collectionService.getItems(collection.id, null, 0L, 10, "es", null) } returns listOf(
            CollectionItem(collectionId = collection.id, childCollectionId = childCollectionId)
        )
        coEvery { collectionService.getLanguageVariant(collection.id, "es") } returns null
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, any<bosca.content.collection.model.ICollection>(), PermissionAction.LIST)
        } returns true
        coEvery { collectionService.getById(childCollectionId) } returns childCollection
        coEvery { collectionService.getLanguageVariant(childCollectionId, "es") } returns null

        val result = controller.items(authentication, collection, 0L, 10, null, "es", null)
        assertEquals(emptyList(), result)
    }

    // ---- expandedMetadata ----

    @Test
    fun `expandedMetadata returns allowed metadata and skips null and denied`() = runTest {
        val collection = createCollection()
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val allowedMetadata = createMetadata(id = allowedId)
        val deniedMetadata = createMetadata(id = deniedId)

        coEvery { collectionService.expandMetadata(collection, null, 0L, 10) } returns listOf(
            CollectionFindResult(null, allowedId, null),
            CollectionFindResult(null, deniedId, null),
            CollectionFindResult(null, null, null)
        )
        coEvery { metadataService.getById(allowedId) } returns allowedMetadata
        coEvery { metadataService.getById(deniedId) } returns deniedMetadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, allowedMetadata, PermissionAction.VIEW)
        } returns true
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, deniedMetadata, PermissionAction.VIEW)
        } returns false

        val result = controller.expandedMetadata(authentication, collection, 0L, 10, null)
        assertEquals(listOf(allowedMetadata), result)
    }

    @Test
    fun `expandedMetadata skips when metadata not found`() = runTest {
        val collection = createCollection()
        val missingId = UUID.random()

        coEvery { collectionService.expandMetadata(collection, null, 0L, 10) } returns listOf(
            CollectionFindResult(null, missingId, null)
        )
        coEvery { metadataService.getById(missingId) } returns null

        assertEquals(emptyList(), controller.expandedMetadata(authentication, collection, 0L, 10, null))
    }

    @Test
    fun `expandedMetadataCount returns zero when list denied`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)
        } returns false

        assertEquals(0L, controller.expandedMetadataCount(authentication, collection, null))
    }

    @Test
    fun `expandedMetadataCount returns service count when list allowed`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)
        } returns true
        coEvery { collectionService.expandMetadataCount(collection, "state") } returns 42L

        assertEquals(42L, controller.expandedMetadataCount(authentication, collection, "state"))
    }

    // ---- parentCollections ----

    @Test
    fun `parentCollections returns allowed parents and skips denied`() = runTest {
        val collection = createCollection()
        val allowed = createCollection()
        val denied = createCollection()

        coEvery { collectionService.getCollectionParents(collection.id, 0L, 10) } returns listOf(allowed, denied)
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, allowed, PermissionAction.VIEW)
        } returns true
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, denied, PermissionAction.VIEW)
        } returns false

        val result = controller.parentCollections(authentication, collection, 0L, 10)
        assertEquals(listOf(allowed), result)
        assertEquals(allowed.attributes, allowed.itemAttributes)
    }

    // ---- permissions ----

    @Test
    fun `permissions returns empty when manage denied`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.MANAGE)
        } returns false

        assertEquals(emptyList(), controller.permissions(authentication, collection))
    }

    @Test
    fun `permissions maps collection permissions when manage allowed`() = runTest {
        val collection = createCollection()
        val groupId = UUID.random()
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.MANAGE)
        } returns true
        coEvery { collectionService.getPermissions(collection) } returns listOf(
            CollectionPermission(collection.id, groupId, PermissionAction.VIEW)
        )

        val result = controller.permissions(authentication, collection)
        assertEquals(1, result.size)
        assertEquals(groupId, result.first().groupId)
        assertEquals(PermissionAction.VIEW, result.first().action)
    }

    // ---- templateMetadata ----

    @Test
    fun `templateMetadata returns null when no template id`() = runTest {
        val collection = createCollection(templateMetadataId = null)
        assertNull(controller.templateMetadata(authentication, collection))
    }

    @Test
    fun `templateMetadata uses versioned lookup when version set`() = runTest {
        val templateId = UUID.random()
        val collection = createCollection(templateMetadataId = templateId, templateMetadataVersion = 3)
        val metadata = createMetadata(id = templateId)
        coEvery { metadataService.getById(templateId, 3) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        } returns true

        assertEquals(metadata, controller.templateMetadata(authentication, collection))
    }

    @Test
    fun `templateMetadata falls back to unversioned lookup when versioned returns null`() = runTest {
        val templateId = UUID.random()
        val collection = createCollection(templateMetadataId = templateId, templateMetadataVersion = 3)
        val metadata = createMetadata(id = templateId)
        coEvery { metadataService.getById(templateId, 3) } returns null
        coEvery { metadataService.getById(templateId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        } returns true

        assertEquals(metadata, controller.templateMetadata(authentication, collection))
    }

    @Test
    fun `templateMetadata uses unversioned lookup when version is null`() = runTest {
        val templateId = UUID.random()
        val collection = createCollection(templateMetadataId = templateId, templateMetadataVersion = null)
        val metadata = createMetadata(id = templateId)
        coEvery { metadataService.getById(templateId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        } returns true

        assertEquals(metadata, controller.templateMetadata(authentication, collection))
    }

    @Test
    fun `templateMetadata returns null when template found but view denied`() = runTest {
        val templateId = UUID.random()
        val collection = createCollection(templateMetadataId = templateId, templateMetadataVersion = null)
        val metadata = createMetadata(id = templateId)
        coEvery { metadataService.getById(templateId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        } returns false

        assertNull(controller.templateMetadata(authentication, collection))
    }

    // ---- workflow ----

    @Test
    fun `workflow verifies edit permission and wraps collection`() = runTest {
        val collection = createCollection()
        coEvery {
            collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        } just Runs

        val workflow = controller.workflow(authentication, collection)
        assertEquals(collection, workflow.collection)
    }

    // ---- languageVariants batch resolver ----

    @Test
    fun `languageVariants maps allowed variants to language tags`() = runTest {
        val outer = Batch<CollectionCacheKeyId, List<String>>(listOf(CollectionCacheKeyId(UUID.random())))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        val allowedVariant = createVariant(languageTag = "es")
        val deniedVariant = createVariant(languageTag = "fr")
        coEvery {
            collectionPermissionEvaluator.isAllowed(
                authentication,
                listOf(allowedVariant, deniedVariant),
                PermissionAction.VIEW
            )
        } returns listOf(true, false)

        controller.languageVariants(authentication, outer)

        // drive the captured BatchMapper so the mapper lambda executes
        captured.captured.setData(0, listOf(allowedVariant, deniedVariant))
        assertEquals(listOf("es"), outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariants maps empty when variants list is null`() = runTest {
        val key = CollectionCacheKeyId(UUID.random())
        val outer = Batch<CollectionCacheKeyId, List<String>>(listOf(key))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, emptyList<CollectionLanguageVariant>(), PermissionAction.VIEW)
        } returns emptyList()

        controller.languageVariants(authentication, outer)

        // Drive the mapper with a null value to exercise the `variants ?: emptyList()` null arms.
        captured.captured.setData(listOf(key), listOf<List<CollectionLanguageVariant>?>(null))
        assertEquals(emptyList(), outer.getData(key))
    }

    // ---- metadataRelationships batch resolver ----

    @Test
    fun `metadataRelationships installs filter that keeps only allowed metadata relationships`() = runTest {
        val collectionId = UUID.random()
        val allowedMetadataId = UUID.random()
        val deniedMetadataId = UUID.random()
        val allowedMetadata = createMetadata(id = allowedMetadataId)
        val deniedMetadata = createMetadata(id = deniedMetadataId)

        val allowedRel = CollectionMetadataRelationship(collectionId, allowedMetadataId, "rel")
        val deniedRel = CollectionMetadataRelationship(collectionId, deniedMetadataId, "rel")

        val key = CollectionCacheKeyId(collectionId)
        val batch = Batch<CollectionCacheKeyId, List<CollectionMetadataRelationship>>(listOf(key))
        batch.setData(key, listOf(allowedRel, deniedRel))

        coEvery { collectionService.addMetadataRelationshipsToBatch(batch) } just Runs
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val inner = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            inner.setData(0, allowedMetadata)
            inner.setData(1, deniedMetadata)
        }
        coEvery {
            metadataPermissionEvaluator.isAllowed(
                authentication,
                listOf(allowedMetadata, deniedMetadata),
                PermissionAction.VIEW
            )
        } returns listOf(true, false)

        controller.metadataRelationships(authentication, batch)

        // getResults() triggers the installed BatchFilter; only the allowed relationship survives.
        val results = batch.getResults()
        assertEquals(1, results.size)
        assertEquals(listOf(allowedRel), results[0])
    }

    @Test
    fun `metadataRelationships filter treats null item data as empty`() = runTest {
        val key = CollectionCacheKeyId(UUID.random())
        val batch = Batch<CollectionCacheKeyId, List<CollectionMetadataRelationship>>(listOf(key))
        // no data set -> item data is null

        coEvery { collectionService.addMetadataRelationshipsToBatch(batch) } just Runs
        coEvery { metadataService.getByIdBatched(any()) } just Runs
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, emptyList<Metadata>(), PermissionAction.VIEW)
        } returns emptyList()

        controller.metadataRelationships(authentication, batch)

        val results = batch.getResults()
        assertEquals(1, results.size)
        assertEquals(emptyList(), results[0])
    }

    // ---- languageVariant (single, most-preferred) batch resolver ----

    private fun languageVariantContext(collection: Collection, argLanguageTag: String? = null): BatchContext<*> =
        BatchContext(
            arguments = if (argLanguageTag != null) mapOf("languageTag" to argLanguageTag) else emptyMap(),
            context = collection
        )

    private fun mockCall(
        queryLanguage: String? = null,
        cookieLanguage: String? = null,
        acceptLanguages: List<String> = emptyList()
    ): ServerCall {
        val request = mockk<ServerRequest>()
        every { request.queryParameters } returns Parameters(
            if (queryLanguage != null) mapOf("language" to listOf(queryLanguage)) else emptyMap()
        )
        every { request.cookies } returns RequestCookies(
            if (cookieLanguage != null) listOf("_language=$cookieLanguage") else emptyList()
        )
        every { request.acceptLanguageItems() } returns acceptLanguages.map { LanguageItem(it, 1.0f) }
        val call = mockk<ServerCall>()
        every { call.request } returns request
        return call
    }

    @Test
    fun `languageVariant returns default variant when present`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")
        val defaultVariant = createVariant(id = collectionId, languageTag = "en")
        collection.defaultLanguageVariant = defaultVariant

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns null
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        val call = mockCall(acceptLanguages = listOf("en"))
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(defaultVariant))
        assertEquals(defaultVariant, outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant returns exact matching variant from argument language tag`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns "ES"
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection, "es"))

        val esVariant = createVariant(id = collectionId, languageTag = "es")
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, listOf(esVariant), PermissionAction.VIEW)
        } returns listOf(true)

        val call = mockCall()
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(esVariant))
        assertEquals(esVariant, outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant wildcard root selects candidate when no per-collection tag`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns "*"
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        val frVariant = createVariant(id = collectionId, languageTag = "fr")
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, listOf(frVariant), PermissionAction.VIEW)
        } returns listOf(true)

        val call = mockCall()
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(frVariant))
        assertEquals(frVariant, outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant matches accept-language when variant tag contained in root tags`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns null
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        val deVariant = createVariant(id = collectionId, languageTag = "de")
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, listOf(deVariant), PermissionAction.VIEW)
        } returns listOf(true)

        val call = mockCall(acceptLanguages = listOf("de"))
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(deVariant))
        assertEquals(deVariant, outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant returns null candidate when nothing matches and variant not allowed`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns null
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        val frVariant = createVariant(id = collectionId, languageTag = "fr")
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, listOf(frVariant), PermissionAction.VIEW)
        } returns listOf(false)

        // query language present but not matching the variant -> not wildcard, not contained
        val call = mockCall(queryLanguage = "it")
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(frVariant))
        assertNull(outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant uses cookie language when no argument or query`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns null
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        val esVariant = createVariant(id = collectionId, languageTag = "es")
        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, listOf(esVariant), PermissionAction.VIEW)
        } returns listOf(true)

        val call = mockCall(cookieLanguage = "es")
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(esVariant))
        assertEquals(esVariant, outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant skips denied variant during iteration`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns null
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        val deniedVariant = createVariant(id = collectionId, languageTag = "es")
        val allowedVariant = createVariant(id = collectionId, languageTag = "de")
        coEvery {
            collectionPermissionEvaluator.isAllowed(
                authentication,
                listOf(deniedVariant, allowedVariant),
                PermissionAction.VIEW
            )
        } returns listOf(false, true)

        val call = mockCall(acceptLanguages = listOf("de"))
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(CollectionCacheKeyId(collectionId)))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(0, listOf(deniedVariant, allowedVariant))
        assertEquals(allowedVariant, outer.getData(outer.keys.first()))
    }

    @Test
    fun `languageVariant returns null when variants list is null`() = runTest {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId, languageTag = "en")

        val environment = mockk<DataFetchingEnvironment>()
        every { environment.getArgument<String>("languageTag") } returns null
        val batchEnvironment = mockk<BatchLoaderEnvironment>()
        every { batchEnvironment.keyContextsList } returns listOf(languageVariantContext(collection))

        coEvery {
            collectionPermissionEvaluator.isAllowed(authentication, emptyList<CollectionLanguageVariant>(), PermissionAction.VIEW)
        } returns emptyList()

        val call = mockCall(acceptLanguages = listOf("en"))
        val key = CollectionCacheKeyId(collectionId)
        val outer = Batch<CollectionCacheKeyId, CollectionLanguageVariant>(listOf(key))
        val captured = slot<Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>>()
        coEvery { collectionService.addLanguageVariantsToBatch(capture(captured)) } just Runs

        controller.languageVariant(authentication, call, environment, batchEnvironment, outer)

        captured.captured.setData(listOf(key), listOf<List<CollectionLanguageVariant>?>(null))
        assertNull(outer.getData(key))
    }
}
