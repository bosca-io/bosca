package bosca.content.collection.jobs

import bosca.content.collection.repository.CollectionItemRepository
import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@JobDefinition(CollectionParentItemCacheInvalidationJob::class, JobQueueNames.contentJobQueue, "collection-parent-item-cache-invalidation")
class CollectionParentItemCacheInvalidationExecutor(
    private val collectionService: CollectionService,
    private val items: CollectionItemRepository
) : AbstractJobExecutor<CollectionParentItemCacheInvalidationJob>(CollectionParentItemCacheInvalidationJob.serializer()) {

    override suspend fun execute() = coroutineScope {
        val job = getJobDefinition()
        val id = job.id ?: return@coroutineScope
        val batchSize = 100
        var offset = 0L
        while (true) {
            val parents = items.getCollectionParents(id, offset, batchSize)
            if (parents.isEmpty()) break
            parents.map { parent ->
                async {
                    collectionService.removeItemsCache(parent.collectionId)
                }
            }.awaitAll()
            if (parents.size < batchSize) break
            offset += batchSize
        }
    }
}
