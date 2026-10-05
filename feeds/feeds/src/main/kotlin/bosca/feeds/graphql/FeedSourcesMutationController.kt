package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.feeds.service.FeedSourceService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Feed source creates + the per-source instance accessor under `FeedsMutation.sources`. Admin. */
@TypeController
class FeedSourcesMutationController(
    private val feedSourceService: FeedSourceService,
    private val groups: GroupEvaluator,
) : GraphQLController<FeedSourcesMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: FeedSourceInput): FeedSource {
        groups.verifyFeedsAdmin(authentication)
        return feedSourceService.create(input)
    }

    @Field
    fun source(id: UUID): FeedSourceMutation = FeedSourceMutation(id)
}
