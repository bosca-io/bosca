package bosca.nats.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.nats.admin.model.JetStreamConfig

/**
 * Resolves fields on the JetStreamConfig GraphQL type, exposing
 * JetStream storage limits including memory, disk, and store directory.
 */
@TypeController
class JetStreamConfigController : GraphQLController<JetStreamConfig> {

    @Field
    fun maxMemory(cfg: JetStreamConfig) = cfg.maxMemory

    @Field
    fun maxStorage(cfg: JetStreamConfig) = cfg.maxStorage

    @Field
    fun storeDir(cfg: JetStreamConfig) = cfg.storeDir
}
