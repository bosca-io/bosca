package bosca.atlas.server.graphql

import bosca.graphql.SchemaRoot

object AtlasSchema : SchemaRoot {
    override val query = Query
    override val mutation = Mutation
    override val subscription = Subscription
}
