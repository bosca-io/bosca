package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamCluster

/**
 * Resolves fields on the JetStreamCluster GraphQL type, providing
 * cluster membership information for JetStream resources.
 */
@TypeController
class JetStreamClusterController : GraphQLController<JetStreamCluster> {

    @Field
    fun leader(cluster: JetStreamCluster) = cluster.leader
}
