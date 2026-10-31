package bosca.atlas.server.graphql

import bosca.di.provide
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.TypeRuntimeWiring
import bosca.profile.profile.graphql.ProfileControllerDispatcher
import bosca.profile.profile.graphql.ProfilesControllerDispatcher
import bosca.profile.profile.graphql.ProfilesMutationControllerDispatcher
import bosca.search.graphql.SearchDocumentControllerDispatcher
import bosca.search.graphql.SearchResultControllerDispatcher
import bosca.search.graphql.SearchResultFacetControllerDispatcher

/** Reuses domain resolvers for the identity fields exposed by this composition's SDL. */
internal class IdentityDispatchersRegistrar : DispatchersRegistrar {
    override suspend fun dispatchers(): Map<String, Dispatcher> =
        listOf(
                ProfilesControllerDispatcher(provide()),
                ProfilesMutationControllerDispatcher(provide()),
                ProfileControllerDispatcher(provide()),
                SearchDocumentControllerDispatcher(provide()),
                SearchResultControllerDispatcher(provide()),
                SearchResultFacetControllerDispatcher(provide()),
            )
            .associate { dispatcher ->
                val source = dispatcher.type
                val fields =
                    SchemaRegistry.registry.fields(source.typeName).orEmpty().mapTo(
                        mutableSetOf()
                    ) {
                        it.name
                    }
                source.typeName to
                    object : Dispatcher {
                        override val type =
                            TypeRuntimeWiring.newTypeWiring(source.typeName)
                                .apply {
                                    source.fieldResolvers
                                        .filterKeys { it in fields }
                                        .forEach { (name, resolver) -> field(name, resolver) }
                                }
                                .build()
                    }
            }
}
