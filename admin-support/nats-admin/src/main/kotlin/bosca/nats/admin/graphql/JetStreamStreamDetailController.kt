package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamCluster
import bosca.nats.admin.model.JetStreamStreamDetail
import bosca.nats.admin.model.JetStreamStreamState

/**
 * Resolves fields on the JetStreamStreamDetail GraphQL type, mapping
 * individual stream details from the NATS JetStream monitoring response.
 */
@TypeController
class JetStreamStreamDetailController : GraphQLController<JetStreamStreamDetail> {

    @Field
    fun name(detail: JetStreamStreamDetail) = detail.name

    @Field
    fun created(detail: JetStreamStreamDetail) = detail.created

    @Field
    fun state(detail: JetStreamStreamDetail): JetStreamStreamState? = detail.state

    @Field
    fun cluster(detail: JetStreamStreamDetail): JetStreamCluster? = detail.cluster
}
