package bosca.profile.organization.jobs

import bosca.di.annotation.ProviderName
import bosca.di.provide
import bosca.profile.configuration.JobQueueNames
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.organization.service.OrganizationService
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
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

@JobDefinition(OrganizationIndexJob::class, JobQueueNames.profileJobQueue, "index-organization")
class OrganizationIndexExecutor(
    @ProviderName(TransformProvider)
    private val transform: Transformation<IndexStorageSystem, Profile, JsonElement>,
    private val organizationService: OrganizationService
) : AbstractJobExecutor<OrganizationIndexJob>(OrganizationIndexJob.serializer()) {

    override suspend fun execute() {
        val searchService: SearchService = provide()
        val profileService: ProfileService = provide()
        val jobConfiguration = getJobDefinition()
        if (jobConfiguration.storage != null) {
            jobConfiguration.storage.execute(searchService, profileService, jobConfiguration)
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

    private suspend fun IndexStorageSystem.execute(searchService: SearchService, profileService: ProfileService, jobConfiguration: OrganizationIndexJob) {
        // Organization documents are profile documents with the organization content type.
        // Only clear those — a full deleteAll would erase the regular profile, metadata,
        // and collection documents sharing this index, racing their rebuild jobs.
        if (jobConfiguration.deleteFirst) {
            searchService.deleteByFilter(
                this,
                SearchFilter.and(
                    SearchFilter.eq("_type", "profile"),
                    SearchFilter.eq("contentType", "bosca/v-profile-organization"),
                )
            )
        }
        if (jobConfiguration.id != null) {
            val organization = organizationService.getOrganization(jobConfiguration.id)
            val profileId = organization.profileId
            if (jobConfiguration.deleteOnly) {
                searchService.delete(this, profileId.toString())
            } else {
                val profile = profileService.getById(profileId)
                val document = if (shouldIndex(profile)) {
                    transform.transform(this, profile)
                } else {
                    null
                }
                if (document == null) {
                    searchService.delete(this, profileId.toString())
                    return
                }
                searchService.index(this, document)
            }
        } else if (!jobConfiguration.deleteOnly) {
            var offset = 0L
            while (true) {
                val profiles = profileService.getAllByType(offset, 500, ProfileType.ORGANIZATION)
                if (profiles.isEmpty()) break
                offset += 500
                log.info("Indexing ${profiles.size} organization items : $offset")
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
            }
        }
    }

    private fun IndexStorageSystem.shouldIndex(profile: Profile): Boolean =
        name == SearchDocumentPipeline.ADMIN_INDEX ||
            (name == SearchDocumentPipeline.PROFILE_INDEX && profile.public && profile.isPublished && profile.isSearchable)

    companion object {

        private val log = LoggerFactory.getLogger(OrganizationIndexExecutor::class.java)

        const val TransformProvider = "profileIndexTransformation"
    }
}
