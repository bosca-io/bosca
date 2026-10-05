package bosca.content.collection.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.search.isContentIndex
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import org.slf4j.LoggerFactory

@JobDefinition(CollectionDeleteFromIndexJob::class, JobQueueNames.contentJobQueue, "index-delete-collection")
class CollectionDeleteFromIndexExecutor(
    private val searchService: SearchService
) : AbstractJobExecutor<CollectionDeleteFromIndexJob>(CollectionDeleteFromIndexJob.serializer()) {

    override suspend fun execute() {
        val jobConfiguration = getJobDefinition()
        if (jobConfiguration.storage != null) {
            jobConfiguration.storage.execute(searchService, jobConfiguration)
        } else {
            val storageService: StorageSystemService = provide()
            val failures = mutableListOf<Exception>()
            storageService.getAll().filter { it.type == StorageSystemType.SEARCH && it.name.isNotBlank() && it.isContentIndex }.forEach {
                try {
                    IndexStorageSystem(it.id, it.name).execute(searchService, jobConfiguration)
                } catch (e: Exception) {
                    log.error("Error deleting collection from index: ${it.name}", e)
                    failures.add(e)
                }
            }
            if (failures.isNotEmpty()) {
                throw failures.first().apply { failures.drop(1).forEach { addSuppressed(it) } }
            }
        }
    }

    private suspend fun IndexStorageSystem.execute(
        searchService: SearchService,
        jobConfiguration: CollectionDeleteFromIndexJob
    ) {
        val id = jobConfiguration.id ?: error("missing id")
        val filter = if (jobConfiguration.languageTag != null) {
            SearchFilter.and(
                SearchFilter.eq("contentId", id.toString()),
                SearchFilter.eq("languageTag", jobConfiguration.languageTag),
            )
        } else {
            SearchFilter.eq("contentId", id.toString())
        }
        searchService.deleteByFilter(this, filter)
    }

    companion object {

        private val log = LoggerFactory.getLogger(CollectionDeleteFromIndexExecutor::class.java)
    }
}
