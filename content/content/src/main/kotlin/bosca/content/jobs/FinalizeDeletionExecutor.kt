package bosca.content.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.MetadataService
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(FinalizeDeletionJob::class, JobQueueNames.contentJobQueue, "finalize-deletion")
class FinalizeDeletionExecutor : AbstractJobExecutor<FinalizeDeletionJob>(FinalizeDeletionJob.serializer()) {

    override suspend fun execute() {
        val metadataService: MetadataService = provide()
        val collectionService: CollectionService = provide()

        finalizeMetadataDeletion(metadataService)
        finalizeCollectionDeletion(collectionService)
    }

    private suspend fun finalizeMetadataDeletion(metadataService: MetadataService) {
        val batchSize = 100
        while (true) {
            val metadata = metadataService.getDeleted(0, batchSize)
            if (metadata.isEmpty()) break
            log.info("Finalizing deletion for ${metadata.size} metadata items")
            var successfulDeletes = 0
            metadata.forEach {
                try {
                    metadataService.delete(it)
                    successfulDeletes++
                } catch (e: Exception) {
                    log.error("Failed to delete metadata ${it.id}", e)
                }
            }
            if (successfulDeletes == 0 && metadata.isNotEmpty()) {
                log.warn("No metadata items were successfully deleted in this batch, stopping to avoid infinite loop.")
                break
            }
        }
    }

    private suspend fun finalizeCollectionDeletion(collectionService: CollectionService) {
        val batchSize = 100
        while (true) {
            val collections = collectionService.getDeleted(0, batchSize)
            if (collections.isEmpty()) break
            log.info("Finalizing deletion for ${collections.size} collections")
            var successfulDeletes = 0
            collections.forEach {
                try {
                    collectionService.permanentlyDelete(it.id)
                    successfulDeletes++
                } catch (e: Exception) {
                    log.error("Failed to permanently delete collection ${it.id}", e)
                }
            }
            if (successfulDeletes == 0 && collections.isNotEmpty()) {
                log.warn("No collections were successfully deleted in this batch, stopping to avoid infinite loop.")
                break
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(FinalizeDeletionExecutor::class.java)
    }
}
