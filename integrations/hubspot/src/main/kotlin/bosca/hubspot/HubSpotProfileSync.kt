package bosca.hubspot

import bosca.di.ObjectProvider
import bosca.hubspot.client.HubSpot
import bosca.hubspot.installer.HubSpotPipelinesInstaller
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer

/**
 * Run the HubSpot sync pipeline for one profile, on demand. A person ([ProfileType.GENERIC]) syncs
 * through the contact pipeline; any other profile through the company pipeline — the type branch the
 * legacy `AddToHubSpotJob` made. [PipelineService.run] drives the chosen pipeline inline under the
 * service account; its suspendable HubSpot nodes run synchronously because a manual run has no run id
 * (see `PipelineNode.run`), so the sync completes — or fails — in line.
 *
 * Shared by the admin sync mutation (one profile) and the bulk `SyncAllHubSpotJob` (per profile on
 * the runner), so the sync logic lives only in the seeded pipelines — no standalone job carries it.
 * No-ops when HubSpot isn't configured (the [hubspot] provider is absent), exactly as the legacy
 * jobs did, so an unconfigured instance neither errors nor enrols anyone.
 */
suspend fun syncProfileToHubSpot(
    profile: Profile,
    hubspot: ObjectProvider<HubSpot>,
    pipelineService: PipelineService,
) {
    if (!hubspot.exists) return
    val key = if (profile.type == ProfileType.GENERIC) {
        HubSpotPipelinesInstaller.SYNC_CONTACT_KEY
    } else {
        HubSpotPipelinesInstaller.SYNC_COMPANY_KEY
    }
    val pipeline = pipelineService.getByKey(key)
        ?: error("HubSpot sync pipeline '$key' is not installed")
    pipelineService.run(pipeline, PipelineValue.of(profile.id, UUIDSerializer()))
}

/** Resolve the profile by id, then sync it (see the [Profile] overload). */
suspend fun syncProfileToHubSpot(
    profileId: UUID,
    hubspot: ObjectProvider<HubSpot>,
    profileService: ProfileService,
    pipelineService: PipelineService,
) {
    if (!hubspot.exists) return
    syncProfileToHubSpot(profileService.getById(profileId), hubspot, pipelineService)
}
