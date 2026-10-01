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
import bosca.security.service.GroupEvaluator

/**
 * Ownership-gated operations on one of the caller's own feed sources under `mySources.source(id)`.
 * Every field verifies the caller owns the source (or is an admin) first; edits keep ownership pinned
 * to the rightful owner. Authorization uses the platform's [ProfileService] + [GroupEvaluator].
 */
@TypeController
class FeedUserSourceMutationController(
    private val feedSourceService: FeedSourceService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<FeedUserSourceMutation> {

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        source: FeedUserSourceMutation,
        input: FeedSourceInput,
    ): FeedSource {
        val owner = verifyFeedSourceOwner(authentication, source.id, feedSourceService, profileService, groupEvaluator)
        val owned = input.copy(
            configuration = input.configuration.copy(ownership = Ownership.USER, ownerProfileId = owner),
        )
        return feedSourceService.update(source.id, owned)
    }

    @Field
    suspend fun enable(authentication: AuthenticationContext, source: FeedUserSourceMutation): FeedSource {
        verifyFeedSourceOwner(authentication, source.id, feedSourceService, profileService, groupEvaluator)
        return feedSourceService.setEnabled(source.id, true) ?: error("feed source ${source.id} not found")
    }

    @Field
    suspend fun disable(authentication: AuthenticationContext, source: FeedUserSourceMutation): FeedSource {
        verifyFeedSourceOwner(authentication, source.id, feedSourceService, profileService, groupEvaluator)
        return feedSourceService.setEnabled(source.id, false) ?: error("feed source ${source.id} not found")
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: FeedUserSourceMutation): Boolean {
        verifyFeedSourceOwner(authentication, source.id, feedSourceService, profileService, groupEvaluator)
        return feedSourceService.delete(source.id)
    }
}
