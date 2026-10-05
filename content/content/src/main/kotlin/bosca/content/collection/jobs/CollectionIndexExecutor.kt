package bosca.content.collection.jobs

import bosca.cache.requestCache
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.content.search.isContentIndex
import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.lock.DistributedLockFactory
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.transformations.Transformation
import bosca.server.BoscaApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.slf4j.LoggerFactory
@JobDefinition(CollectionIndexJob::class, JobQueueNames.contentJobQueue, "index-collection")
class CollectionIndexExecutor(
    @ProviderName(TransformProvider)
    private val transform: Transformation<IndexStorageSystem, Collection, List<JsonElement>>,
    private val distributedLock: DistributedLockFactory,
    application: BoscaApplication
) : AbstractJobExecutor<CollectionIndexJob>(CollectionIndexJob.serializer()) {

    private val defaultBatchSize = application.environment.config.propertyOrNull("content.index.batchSize")
        ?.getString()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: DefaultBatchSize

    override suspend fun execute() {
        val searchService: SearchService = provide()
        val collectionService: CollectionService = provide()
        val job = getJobDefinition()
        if (job.storage != null) {
            if (job.storage.name == null) {
                val storageService: StorageSystemService = provide()
                val system = storageService.get(job.storage.id ?: error("Missing storage system ID")) ?: error("Storage system not found")
                IndexStorageSystem(system.id, system.name).execute(
                    searchService,
                    collectionService,
                    job
                )
            } else {
                job.storage.execute(searchService, collectionService, job)
            }
        } else {
            val storageService: StorageSystemService = provide()
            storageService.getAll().filter { it.type == StorageSystemType.SEARCH && it.isContentIndex }.forEach {
                IndexStorageSystem(it.id, it.name).execute(searchService, collectionService, job)
            }
        }
    }

    private suspend fun IndexStorageSystem.execute(
        searchService: SearchService,
        collectionService: CollectionService,
        job: CollectionIndexJob
    ) {
        // Only clear this job's own document type — a full deleteAll would erase the
        // metadata/profile documents that share this index, racing their rebuild jobs.
        if (job.deleteFirst) searchService.deleteByFilter(this, SearchFilter.eq("_type", "collection"))
        val isAdmin = name == "Admin Search Index"
        val batchSize = job.batchSize?.takeIf { it > 0 } ?: defaultBatchSize
        if (job.id != null) {
            if (job.deleteOnly) {
                searchService.deleteByFilter(this, SearchFilter.eq("contentId", job.id.toString()))
            } else {
                val collection = collectionService.getById(job.id) ?: return
                val documents = if ((isAdmin || (collection.public && collection.isPublished)) && !collection.deleted && (collection.isSearchable || isAdmin)) {
                    transform.transform(this, collection)
                } else {
                    null
                }?.filter { it != JsonNull }
                if (documents == null) {
                    searchService.deleteByFilter(this, SearchFilter.eq("contentId", job.id.toString()))
                } else {
                    searchService.index(this, documents)
                    // Delete unpublished variants from non-admin search indexes
                    if (!isAdmin) {
                        collectionService.getLanguageVariants(job.id)
                            .filter { !it.public || !it.isPublished }
                            .forEach {
                                searchService.deleteByFilter(
                                    this,
                                    SearchFilter.and(
                                        SearchFilter.eq("contentId", job.id.toString()),
                                        SearchFilter.eq("languageTag", it.languageTag),
                                    )
                                )
                            }
                    }
                }
            }
        } else if (!job.deleteOnly) {
            distributedLock.create("collection:index:all").withLock(120_000L, 10_000L, 1000L) {
                var offset = 0L
                while (true) {
                    val collections = collectionService.getAll(offset, batchSize)
                    if (collections.isEmpty()) break
                    offset += batchSize
                    try {
                        log.info("Indexing ${collections.size} collection items : $offset")
                        searchService.index(
                            this,
                            collections.flatMap {
                                if ((isAdmin || (it.public && it.isPublished)) && !it.deleted && (it.isSearchable || isAdmin)) {
                                    transform.transform(this, it)
                                } else {
                                    emptyList()
                                }
                            }
                        )
                    } catch (e: Exception) {
                        log.error("Error indexing collection items : $offset", e)
                    }
                    requestCache().clearLocal()
                }
            }
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(CollectionIndexExecutor::class.java)

        const val TransformProvider = "collectionIndexTransformation"

        private const val DefaultBatchSize = 100
    }
}
