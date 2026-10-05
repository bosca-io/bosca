package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.CampaignInput
import bosca.segmentation.service.CampaignService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object CampaignsMutation

@TypeController
class CampaignsMutationController(
    private val campaignService: CampaignService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<CampaignsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, campaign: CampaignInput): Campaign {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.add(campaign)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, campaign: CampaignInput): Campaign {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.edit(id, campaign)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        campaignService.delete(id)
        return true
    }

    @Field
    suspend fun send(authentication: AuthenticationContext, id: UUID): Campaign {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.send(id)
    }

    @Field
    suspend fun sendTest(authentication: AuthenticationContext, id: UUID, segmentId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        campaignService.sendTest(id, segmentId)
        return true
    }

    @Field
    suspend fun cancel(authentication: AuthenticationContext, id: UUID): Campaign {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.cancel(id)
    }

    @Field
    suspend fun reactivate(authentication: AuthenticationContext, id: UUID): Campaign {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.reactivate(id)
    }
}
