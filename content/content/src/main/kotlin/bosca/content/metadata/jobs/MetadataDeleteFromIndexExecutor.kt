package bosca.content.metadata.jobs

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

@JobDefinition(MetadataDeleteFromIndexJob::class, JobQueueNames.contentJobQueue, "index-delete-metadata")
class MetadataDeleteFromIndexExecutor(
    private val searchService: SearchService
) : AbstractJobExecutor<MetadataDeleteFromIndexJob>(MetadataDeleteFromIndexJob.serializer()) {

    override suspend fun execute() {
        val jobConfiguration = getJobDefinition()
        log.info("Executing delete from index for metadata: {}", jobConfiguration.id)
        if (jobConfiguration.storage != null) {
            jobConfiguration.storage.execute(searchService, jobConfiguration)
        } else {
            val storageService: StorageSystemService = provide()
            val systems = storageService.getAll().filter { it.type == StorageSystemType.SEARCH && it.name.isNotBlank() && it.isContentIndex }
            log.info("Deleting metadata {} from {} search storage systems", jobConfiguration.id, systems.size)
            val failures = mutableListOf<Exception>()
            systems.forEach {
                try {
                    log.info("Deleting metadata {} from index: {}", jobConfiguration.id, it.name)
                    IndexStorageSystem(it.id, it.name).execute(searchService, jobConfiguration)
                    log.info("Successfully deleted metadata {} from index: {}", jobConfiguration.id, it.name)
                } catch (e: Exception) {
                    log.error("Error deleting metadata from index: ${it.name}", e)
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
        jobConfiguration: MetadataDeleteFromIndexJob
    ) {
        val id = jobConfiguration.id ?: error("missing id")
        searchService.deleteByFilter(this, SearchFilter.eq("contentId", id.toString()))
    }

    companion object {

        private val log = LoggerFactory.getLogger(MetadataDeleteFromIndexExecutor::class.java)
    }
}
