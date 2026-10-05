package bosca.content.collection.jobs

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class CollectionMetadataItemRemovedExecutorCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = CollectionMetadataItemRemovedExecutor(collectionService, metadataService)

    /**
     * Builds a [Metadata] mock stubbing only the properties the executor reads.
     */
    private fun metadataMock(
        id: UUID,
        version: Int = 1,
        parentId: UUID? = null,
        syncVariantCollections: Boolean = true,
    ): Metadata = mockk<Metadata>().also {
        every { it.id } returns id
        every { it.version } returns version
        every { it.parentId } returns parentId
        every { it.syncVariantCollections } returns syncVariantCollections
    }

    private suspend fun run(job: CollectionMetadataItemRemovedJob) {
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = CollectionMetadataItemRemovedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject)) {
            executor.execute()
        }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // The generated MetadataIndexJob enqueuer resolves Json from the global DI registry
        // (provide<Json>()) to (de)serialize the job configuration.
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        // The generated MetadataIndexJob.enqueue() resolves the queue by this name.
        provides<JobQueue>("contentQueue") { jobQueue }
        // Run the transaction { } block body directly without a real ConnectionManager.
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    // --- execute(): full happy path with variants, non-null collection ---

    @OptIn(Internal::class)
    @Test
    fun `removes syncing variants from collection and enqueues index jobs`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()
        val syncingVariantId = UUID.random()
        val nonSyncingVariantId = UUID.random()

        val metadata = metadataMock(id = metadataId, version = 3, parentId = null, syncVariantCollections = true)
        val syncingVariant = metadataMock(id = syncingVariantId, version = 7, syncVariantCollections = true)
        val nonSyncingVariant = metadataMock(id = nonSyncingVariantId, version = 9, syncVariantCollections = false)

        coEvery { metadataService.getById(metadataId) } returns metadata
        // parentId == null -> resolves variants by metadata.id; self is filtered out by getVariants.
        coEvery { metadataService.getByParentId(metadataId) } returns listOf(metadata, syncingVariant, nonSyncingVariant)

        val collection = mockk<Collection>()
        every { collection.id } returns collectionId
        coEvery { collectionService.getById(collectionId) } returns collection

        run(CollectionMetadataItemRemovedJob(id = collectionId, metadataId = metadataId))

        // Only the syncing variant is removed.
        coVerify(exactly = 1) { collectionService.removeMetadataItem(collectionId, syncingVariantId) }
        coVerify(exactly = 0) { collectionService.removeMetadataItem(collectionId, nonSyncingVariantId) }
        // collection != null -> variant dirtied.
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(syncingVariantId) }
        // Source metadata always dirtied at the end.
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(metadataId) }
        // Two enqueues: one for the syncing variant, one for the source metadata.
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
    }

    // --- execute(): getVariants uses parentId when present ---

    @OptIn(Internal::class)
    @Test
    fun `resolves variants by parent id when metadata has a parent`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()
        val parentId = UUID.random()
        val variantId = UUID.random()

        val metadata = metadataMock(id = metadataId, parentId = parentId, syncVariantCollections = true)
        val variant = metadataMock(id = variantId, syncVariantCollections = true)

        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataService.getByParentId(parentId) } returns listOf(variant)

        val collection = mockk<Collection>()
        coEvery { collectionService.getById(collectionId) } returns collection

        run(CollectionMetadataItemRemovedJob(id = collectionId, metadataId = metadataId))

        coVerify(exactly = 1) { metadataService.getByParentId(parentId) }
        coVerify(exactly = 0) { metadataService.getByParentId(metadataId) }
        coVerify(exactly = 1) { collectionService.removeMetadataItem(collectionId, variantId) }
    }

    // --- execute(): collection == null skips variant dirtying but still removes + enqueues ---

    @OptIn(Internal::class)
    @Test
    fun `skips variant dirty when collection is missing but still removes and enqueues`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()
        val variantId = UUID.random()

        val metadata = metadataMock(id = metadataId, syncVariantCollections = true)
        val variant = metadataMock(id = variantId, syncVariantCollections = true)

        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery { metadataService.getByParentId(metadataId) } returns listOf(variant)
        coEvery { collectionService.getById(collectionId) } returns null

        run(CollectionMetadataItemRemovedJob(id = collectionId, metadataId = metadataId))

        coVerify(exactly = 1) { collectionService.removeMetadataItem(collectionId, variantId) }
        // collection == null -> the variant is NOT dirtied.
        coVerify(exactly = 0) { metadataService.markCollaborationCollectionsDirty(variantId) }
        // Source metadata still dirtied.
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(metadataId) }
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
    }

    // --- execute(): empty variants returns early (no transaction work) ---

    @OptIn(Internal::class)
    @Test
    fun `returns early when there are no variants`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()

        val metadata = metadataMock(id = metadataId, syncVariantCollections = true)
        coEvery { metadataService.getById(metadataId) } returns metadata
        // getVariants filters out self -> only self returned means empty variants.
        coEvery { metadataService.getByParentId(metadataId) } returns listOf(metadata)

        run(CollectionMetadataItemRemovedJob(id = collectionId, metadataId = metadataId))

        coVerify(exactly = 0) { collectionService.getById(any()) }
        coVerify(exactly = 0) { collectionService.removeMetadataItem(any(), any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationCollectionsDirty(any()) }
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    // --- syncVariantCollections(): guard branch when metadata stops syncing mid-flight ---

    @OptIn(Internal::class)
    @Test
    fun `dirties source only when metadata no longer syncs inside sync body`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()

        val metadata = mockk<Metadata>()
        every { metadata.id } returns metadataId
        // First read (execute() gate) sees true and enters syncVariantCollections;
        // second read (guard inside syncVariantCollections) sees false.
        every { metadata.syncVariantCollections } returnsMany listOf(true, false)

        coEvery { metadataService.getById(metadataId) } returns metadata

        run(CollectionMetadataItemRemovedJob(id = collectionId, metadataId = metadataId))

        // Guard branch: only the source metadata is dirtied, then early return.
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(metadataId) }
        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { collectionService.getById(any()) }
        coVerify(exactly = 0) { collectionService.removeMetadataItem(any(), any()) }
    }

    // --- execute(): gate skips sync when metadata is not syncing at all ---

    @OptIn(Internal::class)
    @Test
    fun `does nothing when metadata does not sync variant collections`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()

        val metadata = metadataMock(id = metadataId, syncVariantCollections = false)
        coEvery { metadataService.getById(metadataId) } returns metadata

        run(CollectionMetadataItemRemovedJob(id = collectionId, metadataId = metadataId))

        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationCollectionsDirty(any()) }
        coVerify(exactly = 0) { collectionService.removeMetadataItem(any(), any()) }
    }

    // --- execute(): error branches ---

    @OptIn(Internal::class)
    @Test
    fun `errors when collection id is missing`() = runTest {
        assertFailsWith<IllegalStateException> {
            run(CollectionMetadataItemRemovedJob(id = null, metadataId = UUID.random()))
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when metadata id is missing`() = runTest {
        assertFailsWith<IllegalStateException> {
            run(CollectionMetadataItemRemovedJob(id = UUID.random(), metadataId = null))
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when metadata is not found`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsWith<IllegalStateException> {
            run(CollectionMetadataItemRemovedJob(id = UUID.random(), metadataId = metadataId))
        }
    }
}
