package bosca.graphql.dispatcher

import bosca.graphql.server.FieldResolver
import bosca.graphql.server.TypeRuntimeWiring

/**
 * Provides Bosca's [TypeRuntimeWiring] for a single object type.
 *
 * KSP generates an implementation of this interface for each [@TypeController][bosca.graphql.annotations.TypeController],
 * wiring the controller's methods as field-level data fetchers.
 */
interface Dispatcher {

    /** The runtime wiring that binds field names to data fetchers for this type. */
    val type: TypeRuntimeWiring
}

/**
 * Combines focused controllers that contribute fields to the same GraphQL object type.
 *
 * Controllers are applied in order. A later controller deterministically replaces an earlier resolver with the
 * same field name, matching the ordering contract used when separate modules extend one GraphQL type.
 */
class CompositeDispatcher(parts: List<Dispatcher>) : Dispatcher {

    override val type: TypeRuntimeWiring

    init {
        require(parts.isNotEmpty()) { "A composite dispatcher needs at least one dispatcher" }
        val typeName = parts.first().type.typeName
        require(parts.all { it.type.typeName == typeName }) {
            "A composite dispatcher cannot combine different GraphQL types"
        }

        val fields = LinkedHashMap<String, FieldResolver>()
        parts.forEach { part ->
            part.type.fieldResolvers.forEach { (field, resolver) -> fields[field] = resolver }
        }
        val typeResolver = parts.lastOrNull { it.type.typeResolver != null }?.type?.typeResolver
        type = TypeRuntimeWiring.newTypeWiring(typeName).apply {
            fields.forEach { (fieldName, resolver) -> field(fieldName, resolver) }
            typeResolver?.let { resolveType(it) }
        }.build()
    }
}
