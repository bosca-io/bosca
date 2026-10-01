package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionItem
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit coverage for [CollectionMetadataItemAddedExecutor] driven through the public [execute] entry
 * point inherited from [CollectionMetadataItemSyncExecutor]. The executor's `syncVariantCollections`
 * override is `protected` and the class is `final`, so every reachable branch is exercised via
 * `execute()`; the internal `!metadata.syncVariantCollections` early-return is unreachable through the
 * public API because `execute()` only calls the override when `syncVariantCollections` is `true`.
 */
@OptIn(InternalDI::class)
class CollectionMetadataItemAddedExecutorCoverageTest {

    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }

    private val executor = CollectionMetadataItemAddedExecutor(collectionService, metadataService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
        coEvery { metadataService.markCollaborationCollectionsDirty(any()) } returns Unit
        coEvery { collectionService.addMetadataItem(any(), any(), any()) } returns Unit
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        parentId: UUID? = null,
        syncVariantCollections: Boolean = true,
        version: Int = 1,
    ): Metadata = Metadata(
        id = id,
        version = version,
        parentId = parentId,
        name = "meta-$id",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        syncVariantCollections = syncVariantCollections,
    )

    private fun collectionItem(
        collectionId: UUID,
        metadataId: UUID,
        attributes: JsonElement? = null,
    ): CollectionItem = CollectionItem(
        collectionId = collectionId,
        childMetadataId = metadataId,
        attributes = attributes,
    )

    /**
     * Drives [CollectionMetadataItemAddedExecutor.execute] with [target] as the job's metadata and
     * [collectionId] as the job's collection, inside a coroutine context that supplies both the
     * running job and a [ConnectionManager] for the nested `transaction { }` blocks.
     */
    @OptIn(Internal::class)
    private suspend fun execute(collectionId: UUID, target: Metadata) {
        val config = CollectionMetadataItemAddedJob(id = collectionId, metadataId = target.id)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataItemAddedExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job) + connectionManager.asCoroutineContext()) {
            executor.execute()
        }
    }

    @Test
    fun `does not enter sync when metadata does not sync variant collections`() = runTest {
        val collectionId = UUID.random()
        val target = metadata(syncVariantCollections = false)
        coEvery { metadataService.getById(target.id) } returns target

        execute(collectionId, target)

        // execute() guards on metadata.syncVariantCollections, so the override never runs.
        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
        coVerify(exactly = 0) { collectionService.getCollectionMetadataItem(any(), any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationCollectionsDirty(any()) }
    }

    @Test
    fun `errors when metadata id is missing on the job`() = runTest {
        val config = CollectionMetadataItemAddedJob(id = UUID.random(), metadataId = null)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataItemAddedExecutor::class,
        )
        var threw = false
        withContext(jobQueue.asCoroutineContext(job) + connectionManager.asCoroutineContext()) {
            try {
                executor.execute()
            } catch (e: IllegalStateException) {
                threw = true
            }
        }
        kotlin.test.assertTrue(threw)
    }

    @Test
    fun `errors when collection id is missing on the job`() = runTest {
        val target = metadata()
        coEvery { metadataService.getById(target.id) } returns target
        val config = CollectionMetadataItemAddedJob(id = null, metadataId = target.id)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataItemAddedExecutor::class,
        )
        var threw = false
        withContext(jobQueue.asCoroutineContext(job) + connectionManager.asCoroutineContext()) {
            try {
                executor.execute()
            } catch (e: IllegalStateException) {
                threw = true
            }
        }
        kotlin.test.assertTrue(threw)
    }

    @Test
    fun `errors when metadata is not found`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null
        val config = CollectionMetadataItemAddedJob(id = collectionId, metadataId = metadataId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionMetadataItemAddedExecutor::class,
        )
        var threw = false
        withContext(jobQueue.asCoroutineContext(job) + connectionManager.asCoroutineContext()) {
            try {
                executor.execute()
            } catch (e: IllegalStateException) {
                threw = true
            }
        }
        kotlin.test.assertTrue(threw)
    }

    @Test
    fun `adds metadata item to a syncing variant and enqueues both variant and parent`() = runTest {
        val collectionId = UUID.random()
        val target = metadata()
        val variant = metadata()
        val attributes = json.parseToJsonElement("""{"role":"primary"}""")

        coEvery { metadataService.getById(target.id) } returns target
        coEvery { metadataService.getByParentId(target.id) } returns listOf(variant, target)
        coEvery { collectionService.getCollectionMetadataItem(collectionId, target.id) } returns
            collectionItem(collectionId, target.id, attributes)

        execute(collectionId, target)

        val variantId = variant.id
        val parentId = target.id
        coVerify(exactly = 1) { collectionService.getCollectionMetadataItem(collectionId, parentId) }
        coVerify(exactly = 1) { collectionService.addMetadataItem(collectionId, variantId, attributes) }
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(variantId) }
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(parentId) }
        // Enqueued once for the variant and once for the parent.
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `skips variants that do not sync variant collections`() = runTest {
        val collectionId = UUID.random()
        val target = metadata()
        val nonSyncing = metadata(syncVariantCollections = false)

        coEvery { metadataService.getById(target.id) } returns target
        coEvery { metadataService.getByParentId(target.id) } returns listOf(nonSyncing, target)
        coEvery { collectionService.getCollectionMetadataItem(collectionId, target.id) } returns
            collectionItem(collectionId, target.id)

        execute(collectionId, target)

        val nonSyncingId = nonSyncing.id
        val parentId = target.id
        // Filtered out: no add / per-variant dirty for the non-syncing variant.
        coVerify(exactly = 0) { collectionService.addMetadataItem(collectionId, nonSyncingId, any()) }
        coVerify(exactly = 0) { metadataService.markCollaborationCollectionsDirty(nonSyncingId) }
        // Parent still marked dirty and enqueued.
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(parentId) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `does parent-only bookkeeping when there are no variants`() = runTest {
        val collectionId = UUID.random()
        val target = metadata()

        coEvery { metadataService.getById(target.id) } returns target
        // Only self is returned; getVariants filters it out leaving an empty list.
        coEvery { metadataService.getByParentId(target.id) } returns listOf(target)
        coEvery { collectionService.getCollectionMetadataItem(collectionId, target.id) } returns
            collectionItem(collectionId, target.id)

        execute(collectionId, target)

        val parentId = target.id
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(parentId) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `catches and logs exception while adding variant then continues`() = runTest {
        val collectionId = UUID.random()
        val target = metadata()
        val variant = metadata()

        coEvery { metadataService.getById(target.id) } returns target
        coEvery { metadataService.getByParentId(target.id) } returns listOf(variant, target)
        coEvery { collectionService.getCollectionMetadataItem(collectionId, target.id) } returns
            collectionItem(collectionId, target.id)
        coEvery { collectionService.addMetadataItem(collectionId, variant.id, any()) } throws
            RuntimeException("boom")

        execute(collectionId, target)

        val variantId = variant.id
        val parentId = target.id
        // The add threw and was caught; the per-variant dirty + enqueue still run after the catch.
        coVerify(exactly = 1) { collectionService.addMetadataItem(collectionId, variantId, any()) }
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(variantId) }
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(parentId) }
        coVerify(exactly = 2) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `resolves variants via parent id when metadata has a parent`() = runTest {
        val collectionId = UUID.random()
        val parentContentId = UUID.random()
        val child = metadata(parentId = parentContentId)
        val sibling = metadata()

        coEvery { metadataService.getById(child.id) } returns child
        // getVariants uses parentId when non-null.
        coEvery { metadataService.getByParentId(parentContentId) } returns listOf(sibling, child)
        coEvery { collectionService.getCollectionMetadataItem(collectionId, child.id) } returns
            collectionItem(collectionId, child.id)

        execute(collectionId, child)

        val siblingId = sibling.id
        coVerify(exactly = 1) { metadataService.getByParentId(parentContentId) }
        coVerify(exactly = 1) { collectionService.addMetadataItem(collectionId, siblingId, any()) }
        coVerify(exactly = 1) { metadataService.markCollaborationCollectionsDirty(siblingId) }
    }
}
