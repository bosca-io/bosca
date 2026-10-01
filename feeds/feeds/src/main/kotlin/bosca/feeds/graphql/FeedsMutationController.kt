package bosca.feeds.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** The `Mutation.feeds` namespace entry points. */
@TypeController
class FeedsMutationController : GraphQLController<FeedsMutation> {

    @Field
    fun sources(): FeedSourcesMutation = FeedSourcesMutation

    @Field
    fun mySources(): FeedUserSourcesMutation = FeedUserSourcesMutation

    @Field
    fun mySubscriptions(): FeedSubscriptionsMutation = FeedSubscriptionsMutation
}
