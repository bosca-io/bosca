package bosca.feeds.graphql

import bosca.content.metadata.model.Metadata
import bosca.feeds.model.FeedSource
import bosca.feeds.service.FeedItemService
import bosca.feeds.service.FeedSourceService
import bosca.feeds.service.FeedSubscriptionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Feed reads under `Query.feeds`. Admin reads (`source`/`sources`/`items`) gate on the feeds admin
 * group; the `my*` reads + the for-you/recommended serve surfaces are per-caller (resolve the
 * authenticated profile via the platform's [ProfileService]).
 */
@TypeController
class FeedsController(
    private val feedSourceService: FeedSourceService,
    private val feedItemService: FeedItemService,
    private val feedSubscriptionService: FeedSubscriptionService,
    private val profileService: ProfileService,
    private val groups: GroupEvaluator,
) : GraphQLController<Feeds> {

    @Field
    suspend fun source(authentication: AuthenticationContext, id: UUID): FeedSource? {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.get(id)
    }

    @Field
    suspend fun sources(authentication: AuthenticationContext, offset: Int, limit: Int): List<FeedSource> {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.getAll(offset, limit)
    }

    @Field
    suspend fun items(
        authentication: AuthenticationContext,
        sourceId: UUID,
        offset: Int,
        limit: Int,
    ): List<Metadata> {
        groups.verifyFeedsAdmin(authentication)
        return feedItemService.getBySource(sourceId, offset, limit)
    }

    @Field
    suspend fun mySources(authentication: AuthenticationContext, offset: Int, limit: Int): List<FeedSource> {
        val profileId = callerProfileId(authentication, profileService)
        return feedSourceService.getByOwner(profileId, offset, limit)
    }

    @Field
    suspend fun mySubscriptions(authentication: AuthenticationContext, offset: Int, limit: Int): List<FeedSource> {
        val profileId = callerProfileId(authentication, profileService)
        return feedSubscriptionService.getSubscribedSources(profileId, offset, limit)
    }

    @Field
    suspend fun myFeed(authentication: AuthenticationContext, offset: Int, limit: Int): List<Metadata> {
        val profileId = callerProfileId(authentication, profileService)
        return feedItemService.getForProfile(profileId, offset, limit)
    }

    @Field
    suspend fun forYou(
        authentication: AuthenticationContext,
        offset: Int,
        limit: Int,
        contextType: String? = null,
    ): List<Metadata> {
        val profileId = callerProfileId(authentication, profileService)
        return feedItemService.getForYou(profileId, offset, limit, contextType)
    }

    @Field
    suspend fun recommended(
        authentication: AuthenticationContext,
        metadataId: UUID,
        limit: Int,
        contextType: String? = null,
    ): List<Metadata> {
        val profileId = callerProfileId(authentication, profileService)
        return feedItemService.getRecommended(metadataId, profileId, limit, contextType)
    }
}
