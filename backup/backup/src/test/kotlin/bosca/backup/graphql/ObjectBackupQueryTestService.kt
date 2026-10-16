package bosca.backup.graphql

import bosca.graphql.GraphQLService
import bosca.graphql.MutationRoot
import bosca.graphql.QueryRoot
import bosca.graphql.SchemaRoot
import bosca.graphql.SubscriptionRoot
import bosca.graphql.dispatcher.BackupDispatchersRegistrar
import bosca.graphql.dispatcher.BackupTestDispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder

/** Executes production backup dispatchers through the real GraphQL runtime. */
class ObjectBackupQueryTestService : GraphQLService(
    object : SchemaRoot {
        override val query: QueryRoot = object : QueryRoot {}
        override val mutation: MutationRoot = object : MutationRoot {}
        override val subscription: SubscriptionRoot = object : SubscriptionRoot {}
    },
    introspectionEnabled = true,
) {
    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = BackupDispatchersRegistrar().dispatchers() +
            BackupTestDispatchersRegistrar().dispatchers()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) = Unit
}
