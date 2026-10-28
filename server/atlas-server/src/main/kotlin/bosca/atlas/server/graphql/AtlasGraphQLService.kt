package bosca.atlas.server.graphql

import bosca.git.configuration.GitSchemaRegistrar
import bosca.artifacts.admin.graphql.ArtifactsAdminSchemaRegistrar
import bosca.graphql.dispatcher.ArtifactsAdminDispatchersRegistrar
import bosca.graphql.GraphQLService
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.graphql.dispatcher.GitCiDispatchersRegistrar
import bosca.graphql.dispatcher.GitDispatchersRegistrar
import bosca.graphql.dispatcher.AtlasDispatchersRegistrar
import bosca.graphql.dispatcher.PipelinesDispatchersRegistrar
import bosca.graphql.dispatcher.SchedulerDispatchersRegistrar
import bosca.graphql.dispatcher.SecurityDispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.pipelines.configuration.PipelinesSchemaRegistrar
import bosca.scheduler.configuration.SchedulerSchemaRegistrar
import bosca.security.configuration.SecuritySchemaRegistrar

/** Atlas composes Git, CI, registry administration, and their supporting API namespaces. */
class AtlasGraphQLService(introspectionEnabled: Boolean) :
    GraphQLService(AtlasSchema, introspectionEnabled) {
    override val dispatchersRegistrar =
        object : DispatchersRegistrar {
            override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
        }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        SchemaRegistry.initialize(*atlasSchemaRegistrars())
        DispatchersRegistry.register(
            builder,
            SecurityDispatchersRegistrar(),
            IdentityDispatchersRegistrar(),
            PipelinesDispatchersRegistrar(),
            SchedulerDispatchersRegistrar(),
            GitDispatchersRegistrar(),
            GitCiDispatchersRegistrar(),
            ArtifactsAdminDispatchersRegistrar(),
            AtlasDispatchersRegistrar(),
        )
    }
}

internal fun atlasSchemaRegistrars() =
    arrayOf(
        AtlasSchemaRegistrar(),
        SecuritySchemaRegistrar(),
        GitSchemaRegistrar(),
        PipelinesSchemaRegistrar(),
        SchedulerSchemaRegistrar(),
        ArtifactsAdminSchemaRegistrar(),
    )
