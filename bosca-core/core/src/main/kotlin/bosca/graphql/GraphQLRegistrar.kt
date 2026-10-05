package bosca.graphql

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import kotlin.reflect.KClass

object GraphQLRegistrar {

    @OptIn(InternalDI::class)
    fun register(service: GraphQLService) {
        ProviderRegistry.register(GraphQLService::class, object : ObjectProvider<GraphQLService> {
            override val type: KClass<GraphQLService> = GraphQLService::class
            override suspend fun get(): GraphQLService = service
        })
    }
}