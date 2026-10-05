package bosca.segmentation.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.segmentation.model.BannerWeight
import bosca.segmentation.model.Campaign
import bosca.segmentation.service.CampaignService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Campaigns

@TypeController
class CampaignsController(
    private val campaignService: CampaignService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Campaigns> {

    @Field
    suspend fun all(authentication: AuthenticationContext, offset: Long, limit: Int): List<Campaign> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.getAll(maxOf(offset, 0), limit.coerceIn(1, 100))
    }

    @Field
    suspend fun campaign(authentication: AuthenticationContext, id: UUID): Campaign? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return campaignService.getById(id)
    }

    /**
     * Resolves the profile to use for banner lookups. If [profileId] is provided, validates
     * ownership against the authenticated principal. Otherwise, returns the first profile
     * belonging to the principal (the default profile).
     *
     * @return the resolved profile ID, or `null` if no profile is available or ownership fails
     */
    private suspend fun resolveProfileId(authentication: AuthenticationContext, profileId: UUID?): UUID? {
        if (profileId != null) {
            val profile = profileService.getById(profileId)
            return if (profile.principal == authentication.principal()?.id) profileId else null
        }
        val principalId = authentication.principal()?.id ?: return null
        return profileService.getByPrincipal(principalId).firstOrNull()?.id
    }

    @Field
    suspend fun activeBanners(authentication: AuthenticationContext, profileId: UUID?, placement: String?): List<Campaign> {
        val resolved = resolveProfileId(authentication, profileId)
        val banners = if (resolved != null) {
            campaignService.getActiveBannersForProfile(resolved)
        } else {
            campaignService.getActiveBanners()
        }
        if (placement != null) {
            return banners.filter { it.placement == placement }
        }
        return banners
    }

    @Field
    suspend fun activeBannerWeights(authentication: AuthenticationContext, profileId: UUID?, placement: String): List<BannerWeight> {
        val resolved = resolveProfileId(authentication, profileId)
        return campaignService.getActiveBannerWeightsForPlacement(resolved, placement)
    }

    @Field
    suspend fun activeBanner(authentication: AuthenticationContext, profileId: UUID?, placement: String): Campaign? {
        val resolved = resolveProfileId(authentication, profileId)
        return campaignService.getActiveBannerForPlacement(resolved, placement)
    }
}
