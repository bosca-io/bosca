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
class CollectionParentItemCacheInvalidationExecutorTest {

    private val collectionService = mockk<CollectionService>()
    private val items = mockk<CollectionItemRepository>()
    private val json = Json { 
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    private val executor = CollectionParentItemCacheInvalidationExecutor(collectionService, items)

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

        coEvery { items.getCollectionParents(childId, 0, 100) } returns listOf(
            CollectionItem(collectionId = parentId1, childCollectionId = childId),
            CollectionItem(collectionId = parentId2, childCollectionId = childId)
        )
        coEvery { collectionService.removeItemsCache(any()) } returns Unit

        val config = CollectionParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getCollectionParents(childId, 0, 100) }
        coVerify(exactly = 1) { collectionService.removeItemsCache(parentId1) }
        coVerify(exactly = 1) { collectionService.removeItemsCache(parentId2) }
    }

    @OptIn(Internal::class)
    @Test
    fun `invalidates cache for all parents across multiple batches`() = runTest {
        val childId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val batch1 = (1..100).map { CollectionItem(collectionId = UUID.random(), childCollectionId = childId) }
        val batch2 = (1..50).map { CollectionItem(collectionId = UUID.random(), childCollectionId = childId) }

        coEvery { items.getCollectionParents(childId, 0, 100) } returns batch1
        coEvery { items.getCollectionParents(childId, 100, 100) } returns batch2
        coEvery { collectionService.removeItemsCache(any()) } returns Unit

        val config = CollectionParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getCollectionParents(childId, 0, 100) }
        coVerify(exactly = 1) { items.getCollectionParents(childId, 100, 100) }
        coVerify(exactly = 150) { collectionService.removeItemsCache(any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does nothing when no parents`() = runTest {
        val childId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        coEvery { items.getCollectionParents(childId, 0, 100) } returns emptyList()

        val config = CollectionParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getCollectionParents(childId, 0, 100) }
        coVerify(exactly = 0) { collectionService.removeItemsCache(any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `stops early when exactly batch size returned and next is empty`() = runTest {
        val childId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val batch1 = (1..100).map { CollectionItem(collectionId = UUID.random(), childCollectionId = childId) }

        coEvery { items.getCollectionParents(childId, 0, 100) } returns batch1
        coEvery { items.getCollectionParents(childId, 100, 100) } returns emptyList()
        coEvery { collectionService.removeItemsCache(any()) } returns Unit

        val config = CollectionParentItemCacheInvalidationJob(id = childId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = CollectionParentItemCacheInvalidationExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 1) { items.getCollectionParents(childId, 0, 100) }
        coVerify(exactly = 1) { items.getCollectionParents(childId, 100, 100) }
        coVerify(exactly = 100) { collectionService.removeItemsCache(any()) }
    }
}
