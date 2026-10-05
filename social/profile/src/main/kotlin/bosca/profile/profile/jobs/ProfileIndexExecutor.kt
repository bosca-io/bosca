package bosca.profile.profile.jobs

import bosca.cache.requestCache
import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.lock.DistributedLockFactory
import bosca.profile.configuration.JobQueueNames
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.transformations.Transformation
import bosca.server.BoscaApplication
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

@JobDefinition(ProfileIndexJob::class, JobQueueNames.profileJobQueue, "index-profile")
class ProfileIndexExecutor(
    @ProviderName(TransformProvider)
    private val transform: Transformation<IndexStorageSystem, Profile, JsonElement>,
    private val distributedLock: DistributedLockFactory,
    application: BoscaApplication
) : AbstractJobExecutor<ProfileIndexJob>(ProfileIndexJob.serializer()) {

    private val defaultBatchSize = application.environment.config.propertyOrNull("content.index.batchSize")
        ?.getString()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: DefaultBatchSize

    override suspend fun execute() {
        val searchService: SearchService = provide()
        val profileService: ProfileService = provide()
        val jobConfiguration = getJobDefinition()
        if (jobConfiguration.storage != null) {
            if (jobConfiguration.storage.name == null) {
                val storageService: StorageSystemService = provide()
                val system = storageService.get(jobConfiguration.storage.id ?: error("Missing storage system ID")) ?: error("Storage system not found")
                IndexStorageSystem(system.id, system.name).execute(
                    searchService,
                    profileService,
                    jobConfiguration
                )
            } else {
                jobConfiguration.storage.execute(searchService, profileService, jobConfiguration)
            }
        } else {
            val storageService: StorageSystemService = provide()
            storageService.getAll().filter {
                it.type == StorageSystemType.SEARCH &&
                    (it.name == SearchDocumentPipeline.PROFILE_INDEX || it.name == SearchDocumentPipeline.ADMIN_INDEX)
            }.forEach {
                IndexStorageSystem(it.id, it.name).execute(searchService, profileService, jobConfiguration)
            }
        }
    }

    private suspend fun IndexStorageSystem.execute(searchService: SearchService, profileService: ProfileService, jobConfiguration: ProfileIndexJob) {
        // Only clear profile documents — the index is shared with metadata and collections,
        // whose rebuild jobs can run concurrently; a full deleteAll would erase their work.
        if (jobConfiguration.deleteFirst) searchService.deleteByFilter(this, SearchFilter.eq("_type", "profile"))
        val batchSize = jobConfiguration.batchSize?.takeIf { it > 0 } ?: defaultBatchSize
        if (jobConfiguration.id != null) {
            if (jobConfiguration.deleteOnly) {
                searchService.delete(this, jobConfiguration.id.toString())
            } else {
                // Null-safe lookup: a delete can race an in-flight index job, and getById throws.
                val profile = profileService.getAllByIds(listOf(jobConfiguration.id)).firstOrNull()
                val document = if (profile != null && shouldIndex(profile)) {
                    transform.transform(this, profile)
                } else {
                    null
                }
                if (document == null) {
                    // The profile is gone or no longer searchable — remove any previously
                    // indexed document so it can't surface as a stale search hit.
                    searchService.delete(this, jobConfiguration.id.toString())
                    return
                }
                searchService.index(this, document)
            }
        } else if (!jobConfiguration.deleteOnly) {
            distributedLock.create("profile:index:all").withLock(120_000L, 10_000L, 1000L) {
                var offset = 0L
                while (true) {
                    val profiles = profileService.getAll(offset, batchSize)
                    if (profiles.isEmpty()) break
                    offset += batchSize
                    try {
                        log.info("Indexing ${profiles.size} profile items : $offset")
                        searchService.index(
                            this,
                            profiles.mapNotNull {
                                if (shouldIndex(it)) {
                                    transform.transform(this, it)
                                } else {
                                    null
                                }
                            }
                        )
                    } catch (e: Exception) {
                        log.error("Error indexing profile items : $offset", e)
                    }
                    requestCache().clearLocal()
                }
            }
        }
    }

    private fun IndexStorageSystem.shouldIndex(profile: Profile): Boolean =
        name == SearchDocumentPipeline.ADMIN_INDEX ||
            (name == SearchDocumentPipeline.PROFILE_INDEX && profile.public && profile.isPublished && profile.isSearchable)

    companion object {

        private val log = LoggerFactory.getLogger(ProfileIndexExecutor::class.java)

        const val TransformProvider = "profileIndexTransformation"

        private const val DefaultBatchSize = 100
    }
}
