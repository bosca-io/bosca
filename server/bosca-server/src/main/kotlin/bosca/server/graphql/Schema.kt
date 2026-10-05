package bosca.server.graphql

import bosca.graphql.MutationRoot
import bosca.graphql.QueryRoot
import bosca.graphql.SchemaRoot
import bosca.graphql.SubscriptionRoot
import bosca.server.graphql.controllers.Mutation
import bosca.server.graphql.controllers.Query
import bosca.server.graphql.controllers.Subscription

object Schema : SchemaRoot {

    override val query: QueryRoot = Query
    override val mutation: MutationRoot = Mutation
    override val subscription: SubscriptionRoot = Subscription
}