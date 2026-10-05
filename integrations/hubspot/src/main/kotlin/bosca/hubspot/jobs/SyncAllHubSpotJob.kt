package bosca.hubspot.jobs

import bosca.di.ObjectProvider
import bosca.hubspot.client.HubSpot
import bosca.hubspot.syncProfileToHubSpot
import bosca.pipelines.service.PipelineService
import bosca.profile.profile.service.ProfileService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

@Serializable
class SyncAllHubSpotJob : IJobDefinition

/**
 * Bulk HubSpot backfill: page every profile and run its sync pipeline (contact or company, by type)
 * via [syncProfileToHubSpot]. This is the one HubSpot job that remains — a profile-by-profile sync of
 * the whole instance is inherently a background batch that can't run inline in a mutation, so the
 * admin `syncAll` enqueues it and it drives the seeded pipelines on the runner. The per-profile sync
 * logic itself lives only in the pipelines.
 */
@JobDefinition(SyncAllHubSpotJob::class, "profileQueue", "hubspot-sync-all")
class SyncAllHubSpotJobExecutor(
    private val profileService: ProfileService,
    private val pipelineService: PipelineService,
    private val hubspot: ObjectProvider<HubSpot>,
) : AbstractJobExecutor<SyncAllHubSpotJob>(SyncAllHubSpotJob.serializer()) {

    override suspend fun execute() {
        if (!hubspot.exists) return
        var offset = 0L
        val limit = 100
        while (true) {
            val profiles = profileService.getAll(offset, limit)
            if (profiles.isEmpty()) break
            profiles.forEach { profile ->
                syncProfileToHubSpot(profile, hubspot, pipelineService)
            }
            offset += limit.toLong()
        }
    }
}
