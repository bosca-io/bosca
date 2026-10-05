package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.feeds.service.FeedSourceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one feed source. Admin. */
@TypeController
class FeedSourceMutationController(
    private val feedSourceService: FeedSourceService,
    private val groups: GroupEvaluator,
) : GraphQLController<FeedSourceMutation> {

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        source: FeedSourceMutation,
        input: FeedSourceInput,
    ): FeedSource {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.update(source.id, input)
    }

    @Field
    suspend fun enable(authentication: AuthenticationContext, source: FeedSourceMutation): FeedSource {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.setEnabled(source.id, true) ?: error("feed source ${source.id} not found")
    }

    @Field
    suspend fun disable(authentication: AuthenticationContext, source: FeedSourceMutation): FeedSource {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.setEnabled(source.id, false) ?: error("feed source ${source.id} not found")
    }

    @Field
    suspend fun fetch(authentication: AuthenticationContext, source: FeedSourceMutation): Boolean {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.fetchNow(source.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: FeedSourceMutation): Boolean {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.delete(source.id)
    }
}
