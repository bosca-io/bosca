package bosca.content.metadata.jobs

import bosca.cache.requestCache
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.lock.DistributedLockFactory
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.content.search.isContentIndex
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.transformations.Transformation
import bosca.server.BoscaApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.slf4j.LoggerFactory

@JobDefinition(MetadataIndexJob::class, JobQueueNames.contentJobQueue, "index-metadata")
class MetadataIndexExecutor(
    @ProviderName(TransformProvider)
    private val transform: Transformation<IndexStorageSystem, Metadata, JsonElement?>,
    private val distributedLock: DistributedLockFactory,
    application: BoscaApplication
) : AbstractJobExecutor<MetadataIndexJob>(MetadataIndexJob.serializer()) {

    private val defaultBatchSize = application.environment.config.propertyOrNull("content.index.batchSize")
        ?.getString()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: DefaultBatchSize

    override suspend fun execute() {
        val searchService: SearchService = provide()
        val metadataService: MetadataService = provide()
        val jobConfiguration = getJobDefinition()
        if (jobConfiguration.storage != null) {
            if (jobConfiguration.storage.name == null) {
                val storageService: StorageSystemService = provide()
                val system = storageService.get(jobConfiguration.storage.id ?: error("Missing storage system ID")) ?: error("Storage system not found")
                IndexStorageSystem(system.id, system.name).execute(
                    searchService,
                    metadataService,
                    jobConfiguration
                )
            } else {
                jobConfiguration.storage.execute(searchService, metadataService, jobConfiguration)
            }
        } else {
            val storageService: StorageSystemService = provide()
            storageService.getAll().filter { it.type == StorageSystemType.SEARCH && it.name.isNotBlank() && it.isContentIndex }.forEach {
                IndexStorageSystem(it.id, it.name).execute(searchService, metadataService, jobConfiguration)
            }
        }
    }

    private suspend fun IndexStorageSystem.execute(
        searchService: SearchService,
        metadataService: MetadataService,
        jobConfiguration: MetadataIndexJob
    ) {
        if (jobConfiguration.deleteFirst) {
            // Only clear this job's own document type. The index is shared with collections
            // and profiles, and their rebuild jobs can run concurrently with this one — a
            // full deleteAll here would erase whatever the sibling jobs already re-added.
            searchService.deleteByFilter(this, SearchFilter.eq("_type", "metadata"))
        }
        val isAdmin = name == "Admin Search Index"
        val batchSize = jobConfiguration.batchSize?.takeIf { it > 0 } ?: defaultBatchSize
        if (jobConfiguration.id != null) {
            if (jobConfiguration.deleteOnly) {
                searchService.deleteByFilter(this, SearchFilter.eq("contentId", jobConfiguration.id.toString()))
            } else {
                val metadata = metadataService.getById(jobConfiguration.id) ?: return
                val document = if ((isAdmin || (metadata.public && metadata.isPublished)) && !metadata.deleted && (metadata.isSearchable || isAdmin)) {
                    transform.transform(this, metadata)
                } else {
                    null
                }?.takeIf { it != JsonNull }
                if (document == null) {
                    searchService.deleteByFilter(this, SearchFilter.eq("contentId", jobConfiguration.id.toString()))
                } else {
                    searchService.index(this, document)
                }
            }
        } else if (!jobConfiguration.deleteOnly) {
            distributedLock.create("metadata:index:all").withLock(120_000L, 10_000L, 1000L) {
                var offset = 0L
                while (true) {
                    val metadata = metadataService.getAll(offset, batchSize)
                    if (metadata.isEmpty()) break
                    offset += batchSize
                    try {
                        log.info("Indexing ${metadata.size} metadata items : $offset")
                        searchService.index(
                            this,
                            metadata.mapNotNull {
                                if ((isAdmin || (it.public && it.isPublished)) && !it.deleted && (it.isSearchable || isAdmin)) {
                                    try {
                                        transform.transform(this, it)
                                    } catch (e: Exception) {
                                        log.error("Error transforming metadata item : ${it.id}", e)
                                        null
                                    }
                                } else {
                                    null
                                }
                            }.filter { it != JsonNull }
                        )
                    } catch (e: Exception) {
                        log.error("Error indexing metadata items : $offset", e)
                    }
                    requestCache().clearLocal()
                }
            }
        }
    }

    companion object Companion {

        private val log = LoggerFactory.getLogger(MetadataIndexExecutor::class.java)

        const val TransformProvider = "metadataIndexTransformation"

        private const val DefaultBatchSize = 100
    }
}
