package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamConfig
import bosca.nats.admin.model.JetStreamServerConfig
import bosca.nats.admin.model.JetStreamStats

/**
 * Resolves fields on the JetStreamServerConfig GraphQL type, providing
 * access to JetStream's storage configuration and runtime statistics.
 */
@TypeController
class JetStreamServerConfigController : GraphQLController<JetStreamServerConfig> {

    @Field
    fun config(cfg: JetStreamServerConfig): JetStreamConfig? = cfg.config

    @Field
    fun stats(cfg: JetStreamServerConfig): JetStreamStats? = cfg.stats
}
