package bosca.profile.profile.graphql

import bosca.di.ObjectProvider
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.hubspot.client.HubSpot
import bosca.hubspot.jobs.SyncAllHubSpotJob
import bosca.hubspot.jobs.enqueue
import bosca.hubspot.syncProfileToHubSpot
import bosca.pipelines.service.PipelineService
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
class ThirdPartyExtensionMutation

@TypeController
class ThirdPartyExtensionMutationController(
    private val groupEvaluator: GroupEvaluator,
    private val profileService: ProfileService,
    private val pipelineService: PipelineService,
    private val hubspot: ObjectProvider<HubSpot>,
) : GraphQLController<ThirdPartyExtensionMutation> {

    @Field
    suspend fun syncAll(authenticationContext: AuthenticationContext): Boolean {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        // A full-instance backfill is a background batch (it can't run inline in a mutation), so it
        // runs on the runner — the job drives the seeded HubSpot sync pipeline per profile.
        SyncAllHubSpotJob().enqueue()
        return true
    }

    @Field
    suspend fun sync(authenticationContext: AuthenticationContext, profileId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        // Invoke the seeded HubSpot sync pipeline directly (no event, no standalone job): contact or
        // company by profile type, run inline under the service account.
        syncProfileToHubSpot(profileId, hubspot, profileService, pipelineService)
        return true
    }
}
