package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.feeds.model.Ownership
import bosca.feeds.service.FeedSourceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * Self-service feed source registration for the authenticated caller under `FeedsMutation.mySources`.
 * Requires only an authenticated principal (not the feeds admin group); the new source is forced to be
 * USER-owned by the caller's profile, and inherits the overlap rule via the shared create path.
 */
@TypeController
class FeedUserSourcesMutationController(
    private val feedSourceService: FeedSourceService,
    private val profileService: ProfileService,
) : GraphQLController<FeedUserSourcesMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: FeedSourceInput): FeedSource {
        val profileId = callerProfileId(authentication, profileService)
        val owned = input.copy(
            configuration = input.configuration.copy(ownership = Ownership.USER, ownerProfileId = profileId),
        )
        return feedSourceService.create(owned)
    }

    @Field
    fun source(id: UUID): FeedUserSourceMutation = FeedUserSourceMutation(id)
}
