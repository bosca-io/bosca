package bosca.profile.organization.graphql

import bosca.configuration.CoreSchemaRegistrar
import bosca.graphql.GraphQLController
import bosca.graphql.GraphQLService
import bosca.graphql.MutationRoot
import bosca.graphql.QueryRoot
import bosca.graphql.SchemaRegistrar
import bosca.graphql.SchemaRegistry
import bosca.graphql.SchemaRoot
import bosca.graphql.SubscriptionRoot
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.graphql.dispatcher.ProfileDispatchersRegistrar
import bosca.graphql.server.ExtendedScalars
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.graphql.server.TypeRuntimeWiring
import bosca.profile.configuration.ProfileSchemaRegistrar

object OrganizationsTestSchema : SchemaRoot {
    override val query: QueryRoot = OrganizationsTestQuery
    override val mutation: MutationRoot = OrganizationsTestMutation
    override val subscription: SubscriptionRoot
        get() = TODO("Not yet implemented")
}

object OrganizationsTestQuery : QueryRoot

object OrganizationsTestMutation : MutationRoot

@TypeController
class OrganizationsTestQueryTypeController : GraphQLController<OrganizationsTestQuery> {

    @Field
    fun organizations() = Organizations
}

@TypeController
class OrganizationsTestMutationTypeController : GraphQLController<OrganizationsTestMutation> {

    @Field
    fun organizations() = OrganizationsMutation
}

class OrganizationsTestSchemaRegistrar : SchemaRegistrar {
    override suspend fun load(): String {
        return """
            schema {
                query: OrganizationsTestQuery
                mutation: OrganizationsTestMutation
            }            
            type OrganizationsTestQuery {
                organizations: Organizations
            }
            type OrganizationsTestMutation {
                organizations: OrganizationsMutation
            }            
            # Missing types required by OrganizationService
            type Group {
                id: UUID!
                name: String!
                description: String
                type: GroupType!
            }
            enum GroupType {
                PRINCIPAL
                SYSTEM
            }
            type EntityPermission {
                groupId: UUID!
                action: PermissionAction!
            }
            enum PermissionAction {
                VIEW
                LIST
                EDIT
                DELETE
                MANAGE
                EXECUTE
                IMPERSONATE
            }            
            type Principal {
                id: UUID!
                name: String
            }
            type Collection {
                id: UUID!
                name: String
            }
            scalar Metadata
            scalar Any
            enum CollectionType {
                MANUAL
                AUTOMATIC
            }
        """
    }
}

class TestProfileDispatchersRegistrar : DispatchersRegistrar {
    override suspend fun dispatchers(): Map<String, Dispatcher> {
        val query = OrganizationsTestQueryTypeController()
        val mutation = OrganizationsTestMutationTypeController()
        return mapOf(
            "OrganizationsTestQuery" to object : Dispatcher {
                override val type: TypeRuntimeWiring = TypeRuntimeWiring.newTypeWiring("OrganizationsTestQuery")
                    .field("organizations") { query.organizations() }
                    .build()
            },
            "OrganizationsTestMutation" to object : Dispatcher {
                override val type: TypeRuntimeWiring = TypeRuntimeWiring.newTypeWiring("OrganizationsTestMutation")
                    .field("organizations") { mutation.organizations() }
                    .build()
            },
        )
    }
}

class OrganizationsTestGraphQLService : GraphQLService(OrganizationsTestSchema, true) {
    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        SchemaRegistry.initialize(
            CoreSchemaRegistrar(),
            OrganizationsTestSchemaRegistrar(),
            ProfileSchemaRegistrar()
        )

        builder.scalar("Metadata", ExtendedScalars.Json)
        builder.scalar("Any", ExtendedScalars.Json)

        DispatchersRegistry.register(
            builder,
            TestProfileDispatchersRegistrar(),
            ProfileDispatchersRegistrar(),
        )
    }
}
