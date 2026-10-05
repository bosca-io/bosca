package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionLanguageVariantControllerCoverageTest {

    private val slugService = mockk<SlugService>()
    private val collectionService = mockk<CollectionService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val controller = CollectionLanguageVariantController(
        slugService = slugService,
        collectionService = collectionService,
        metadataService = metadataService,
        metadataPermissionEvaluator = metadataPermissionEvaluator,
    )
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun variant(
        public: Boolean = false,
        publicList: Boolean = false,
        publicSupplementary: Boolean = false,
        searchable: Boolean = true,
        ready: OffsetDateTime? = null,
    ) = CollectionLanguageVariant(
        id = UUID.random(),
        languageTag = "es",
        name = "Spanish",
        public = public,
        publicList = publicList,
        publicSupplementary = publicSupplementary,
        searchable = searchable,
        ready = ready,
    )

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "Meta $id",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
    )

    private fun relationship(collectionId: UUID, metadataId: UUID) =
        CollectionLanguageVariantMetadataRelationship(
            collectionId = collectionId,
            metadataId = metadataId,
            languageTag = "es",
            relationship = "child",
            attributes = null,
        )

    // ---- simple @Field accessors not covered by the sibling test ----

    @Test
    fun `public returns variant public`() {
        assertEquals(true, controller.public(variant(public = true)))
        assertEquals(false, controller.public(variant(public = false)))
    }

    @Test
    fun `publicList returns variant publicList`() {
        assertEquals(true, controller.publicList(variant(publicList = true)))
        assertEquals(false, controller.publicList(variant(publicList = false)))
    }

    @Test
    fun `publicSupplementary returns variant publicSupplementary`() {
        assertEquals(true, controller.publicSupplementary(variant(publicSupplementary = true)))
        assertEquals(false, controller.publicSupplementary(variant(publicSupplementary = false)))
    }

    @Test
    fun `searchable returns variant searchable`() {
        assertEquals(true, controller.searchable(variant(searchable = true)))
        assertEquals(false, controller.searchable(variant(searchable = false)))
    }

    @Test
    fun `ready returns variant ready`() {
        val now = OffsetDateTime.now()
        assertEquals(now, controller.ready(variant(ready = now)))
        assertNull(controller.ready(variant(ready = null)))
    }

    // ---- slug ----

    @Test
    fun `slug delegates to slug service`() = runTest {
        val batch = Batch<CollectionCacheKeyId, String>(keys = listOf(CollectionCacheKeyId(UUID.random())))
        coEvery { slugService.addCollectionSlugsToBatch(batch) } just Runs

        controller.slug(batch)

        coVerify(exactly = 1) { slugService.addCollectionSlugsToBatch(batch) }
    }

    // ---- metadataRelationships filter ----

    @Test
    fun `metadataRelationships installs filter that keeps only allowed relationships`() = runTest {
        val collectionId = UUID.random()
        val allowedMetaId = UUID.random()
        val deniedMetaId = UUID.random()

        val allowedRel = relationship(collectionId, allowedMetaId)
        val deniedRel = relationship(collectionId, deniedMetaId)

        val batch = Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>(
            keys = listOf(CollectionCacheKeyId(collectionId)),
        )
        // populate the batch with data prior to filtering
        batch.setData(0, listOf(allowedRel, deniedRel))

        coEvery { collectionService.addVariantMetadataRelationshipsToBatch(batch) } just Runs
        // populate the inner metadata batch created inside the filter
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val inner = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            inner.keys.forEachIndexed { index, key ->
                inner.setData(index, metadata(key.id))
            }
        }
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } coAnswers {
            val list = secondArg<List<Metadata>>()
            list.map { it.id == allowedMetaId }
        }

        controller.metadataRelationships(authentication, batch)

        val results = batch.getResults()
        assertEquals(1, results.size)
        val kept = results[0]
        assertEquals(listOf(allowedRel), kept)
        coVerify(exactly = 1) { collectionService.addVariantMetadataRelationshipsToBatch(batch) }
    }

    @Test
    fun `metadataRelationships returns empty list when item has no data`() = runTest {
        val collectionId = UUID.random()
        val batch = Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>(
            keys = listOf(CollectionCacheKeyId(collectionId)),
        )
        // no setData -> item.data stays null

        coEvery { collectionService.addVariantMetadataRelationshipsToBatch(batch) } just Runs
        coEvery { metadataService.getByIdBatched(any()) } just Runs
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } returns emptyList()

        controller.metadataRelationships(authentication, batch)

        val results = batch.getResults()
        assertEquals(1, results.size)
        assertEquals(emptyList<CollectionLanguageVariantMetadataRelationship>(), results[0])
    }

    @Test
    fun `metadataRelationships filters out relationship missing from allowed map`() = runTest {
        val collectionId = UUID.random()
        val metaId = UUID.random()
        val rel = relationship(collectionId, metaId)

        val batch = Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>(
            keys = listOf(CollectionCacheKeyId(collectionId)),
        )
        batch.setData(0, listOf(rel))

        coEvery { collectionService.addVariantMetadataRelationshipsToBatch(batch) } just Runs
        // return no metadata at all -> allowedMap is empty -> lookup falls to `?: false`
        coEvery { metadataService.getByIdBatched(any()) } just Runs
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } returns emptyList()

        controller.metadataRelationships(authentication, batch)

        val results = batch.getResults()
        assertEquals(1, results.size)
        assertEquals(emptyList<CollectionLanguageVariantMetadataRelationship>(), results[0])
    }

    @Test
    fun `metadataRelationships works with null authentication`() = runTest {
        val collectionId = UUID.random()
        val metaId = UUID.random()
        val rel = relationship(collectionId, metaId)

        val batch = Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>(
            keys = listOf(CollectionCacheKeyId(collectionId)),
        )
        batch.setData(0, listOf(rel))

        coEvery { collectionService.addVariantMetadataRelationshipsToBatch(batch) } just Runs
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val inner = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            inner.keys.forEachIndexed { index, key ->
                inner.setData(index, metadata(key.id))
            }
        }
        coEvery {
            metadataPermissionEvaluator.isAllowed(null, any<List<Metadata>>(), PermissionAction.VIEW)
        } coAnswers {
            val list = secondArg<List<Metadata>>()
            list.map { true }
        }

        controller.metadataRelationships(null, batch)

        val results = batch.getResults()
        assertEquals(1, results.size)
        assertEquals(listOf(rel), results[0])
    }

    @Test
    fun `metadataRelationships keeps relationships with non-null attributes across multiple items`() = runTest {
        val collectionA = UUID.random()
        val collectionB = UUID.random()
        val metaA = UUID.random()
        val metaB = UUID.random()

        val relA = CollectionLanguageVariantMetadataRelationship(
            collectionId = collectionA,
            metadataId = metaA,
            languageTag = "es",
            relationship = "child",
            attributes = JsonPrimitive("x"),
        )
        val relB = relationship(collectionB, metaB)

        val batch = Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>(
            keys = listOf(CollectionCacheKeyId(collectionA), CollectionCacheKeyId(collectionB)),
        )
        batch.setData(0, listOf(relA))
        // second item deliberately left without data -> `it.data ?: emptyList()` null arm on flatMap

        coEvery { collectionService.addVariantMetadataRelationshipsToBatch(batch) } just Runs
        coEvery { metadataService.getByIdBatched(any()) } coAnswers {
            val inner = firstArg<Batch<MetadataCacheKeyId, Metadata>>()
            inner.keys.forEachIndexed { index, key ->
                inner.setData(index, metadata(key.id))
            }
        }
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, any<List<Metadata>>(), PermissionAction.VIEW)
        } coAnswers {
            val list = secondArg<List<Metadata>>()
            list.map { true }
        }

        controller.metadataRelationships(authentication, batch)

        val results = batch.getResults()
        assertEquals(2, results.size)
        assertEquals(listOf(relA), results[0])
        assertTrue(results[1]?.isEmpty() == true)
    }
}
