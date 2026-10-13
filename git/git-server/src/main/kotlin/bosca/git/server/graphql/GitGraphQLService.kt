package bosca.git.server.graphql

import bosca.git.configuration.GitSchemaRegistrar
import bosca.graphql.GraphQLService
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.graphql.dispatcher.GitCiDispatchersRegistrar
import bosca.graphql.dispatcher.GitDispatchersRegistrar
import bosca.graphql.dispatcher.GitServerDispatchersRegistrar
import bosca.graphql.dispatcher.PipelinesDispatchersRegistrar
import bosca.graphql.dispatcher.SchedulerDispatchersRegistrar
import bosca.graphql.dispatcher.SecurityDispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.pipelines.configuration.PipelinesSchemaRegistrar
import bosca.scheduler.configuration.SchedulerSchemaRegistrar
import bosca.security.configuration.SecuritySchemaRegistrar

/** The standalone Git API composes domain controllers without the main API server. */
class GitGraphQLService(introspectionEnabled: Boolean) :
    GraphQLService(GitSchema, introspectionEnabled) {
    override val dispatchersRegistrar =
        object : DispatchersRegistrar {
            override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
        }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        SchemaRegistry.initialize(*gitSchemaRegistrars())
        DispatchersRegistry.register(
            builder,
            SecurityDispatchersRegistrar(),
            IdentityDispatchersRegistrar(),
            PipelinesDispatchersRegistrar(),
            SchedulerDispatchersRegistrar(),
            GitDispatchersRegistrar(),
            GitCiDispatchersRegistrar(),
            GitServerDispatchersRegistrar(),
        )
    }
}

internal fun gitSchemaRegistrars() =
    arrayOf(
        GitServerSchemaRegistrar(),
        SecuritySchemaRegistrar(),
        GitSchemaRegistrar(),
        PipelinesSchemaRegistrar(),
        SchedulerSchemaRegistrar(),
    )
