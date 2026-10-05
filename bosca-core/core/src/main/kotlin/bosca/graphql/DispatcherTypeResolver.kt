package bosca.graphql

import bosca.graphql.server.TypeResolver
import bosca.graphql.server.TypeRuntimeWiring

class DispatcherTypeResolver : TypeResolver {
    override fun resolveType(value: Any?): String? = value?.let { it::class.simpleName }
}

@Suppress("FunctionName")
fun DispatcherRuntimeType(name: String): TypeRuntimeWiring = TypeRuntimeWiring
    .newTypeWiring(name)
    .resolveType(DispatcherTypeResolver())
    .build()
