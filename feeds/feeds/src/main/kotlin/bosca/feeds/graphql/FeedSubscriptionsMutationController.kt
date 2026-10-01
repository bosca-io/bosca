package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.feeds.service.FeedSubscriptionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * Self-service feed-source subscriptions for the authenticated caller under
 * `FeedsMutation.mySubscriptions`. Caller-scoped (any authenticated principal); operations act on the
 * caller's own profile, resolved via the platform's [ProfileService].
 */
@TypeController
class FeedSubscriptionsMutationController(
    private val feedSubscriptionService: FeedSubscriptionService,
    private val profileService: ProfileService,
) : GraphQLController<FeedSubscriptionsMutation> {

    @Field
    suspend fun subscribe(authentication: AuthenticationContext, sourceId: UUID): FeedSource {
        val profileId = callerProfileId(authentication, profileService)
        return feedSubscriptionService.subscribe(profileId, sourceId)
    }

    @Field
    suspend fun unsubscribe(authentication: AuthenticationContext, sourceId: UUID): Boolean {
        val profileId = callerProfileId(authentication, profileService)
        return feedSubscriptionService.unsubscribe(profileId, sourceId)
    }
}
