package bosca.git.server.graphql

import bosca.graphql.SchemaRoot

object GitSchema : SchemaRoot {
    override val query = Query
    override val mutation = Mutation
    override val subscription = Subscription
}
