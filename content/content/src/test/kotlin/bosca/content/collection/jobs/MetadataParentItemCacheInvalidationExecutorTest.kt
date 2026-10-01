package bosca.content.collection.jobs

import bosca.content.collection.model.CollectionItem
import bosca.content.collection.repository.CollectionItemRepository
import bosca.content.collection.service.CollectionService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class MetadataParentItemCacheInvalidationExecutorTest {

    private val collectionService = mockk<CollectionService>()
    private val items = mockk<CollectionItemRepository>()
    private val json = Json { 
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    private val executor = MetadataParentItemCacheInvalidationExecutor(collectionService, items)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    @Test
    fun `invalidates cache for all parents in single batch`() = runTest {
        val childId = UUID.random()
        val parentId1 = UUID.random()
        val parentId2 = UUID.random()
        val jobQueue = mockk<JobQueue>()

        coEvery { items.getMetadataParents(childId, 0, 100) } returns listOf(
            CollectionItem(collectionId = parentId1, childMetadataId = childId),
            CollectionItem(collectionId = parentId2, childMetadataId = childId)
        )
        coEvery { collectionService.removeItemsCache(any()) } returns Unit

        val config = MetadataParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getMetadataParents(childId, 0, 100) }
        coVerify(exactly = 1) { collectionService.removeItemsCache(parentId1) }
        coVerify(exactly = 1) { collectionService.removeItemsCache(parentId2) }
    }

    @OptIn(Internal::class)
    @Test
    fun `invalidates cache for all parents across multiple batches`() = runTest {
        val childId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val batch1 = (1..100).map { CollectionItem(collectionId = UUID.random(), childMetadataId = childId) }
        val batch2 = (1..50).map { CollectionItem(collectionId = UUID.random(), childMetadataId = childId) }

        coEvery { items.getMetadataParents(childId, 0, 100) } returns batch1
        coEvery { items.getMetadataParents(childId, 100, 100) } returns batch2
        coEvery { collectionService.removeItemsCache(any()) } returns Unit

        val config = MetadataParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getMetadataParents(childId, 0, 100) }
        coVerify(exactly = 1) { items.getMetadataParents(childId, 100, 100) }
        coVerify(exactly = 150) { collectionService.removeItemsCache(any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does nothing when no parents`() = runTest {
        val childId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        coEvery { items.getMetadataParents(childId, 0, 100) } returns emptyList()

        val config = MetadataParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getMetadataParents(childId, 0, 100) }
        coVerify(exactly = 0) { collectionService.removeItemsCache(any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `stops early when exactly batch size returned and next is empty`() = runTest {
        val childId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val batch1 = (1..100).map { CollectionItem(collectionId = UUID.random(), childMetadataId = childId) }

        coEvery { items.getMetadataParents(childId, 0, 100) } returns batch1
        coEvery { items.getMetadataParents(childId, 100, 100) } returns emptyList()
        coEvery { collectionService.removeItemsCache(any()) } returns Unit

        val config = MetadataParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = MetadataParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getMetadataParents(childId, 0, 100) }
        coVerify(exactly = 1) { items.getMetadataParents(childId, 100, 100) }
        coVerify(exactly = 100) { collectionService.removeItemsCache(any()) }
    }
}
