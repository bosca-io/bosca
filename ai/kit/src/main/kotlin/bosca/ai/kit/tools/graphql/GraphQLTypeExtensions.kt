package bosca.ai.kit.tools.graphql

import bosca.graphql.language.ListType
import bosca.graphql.language.NamedType
import bosca.graphql.language.NonNullType
import bosca.graphql.language.Type

/** Renders a GraphQL type reference to its SDL-style name (with `!` and `[...]` wrappers). */
internal fun Type.getGraphQLTypeName(): String = when (this) {
    is NamedType -> name
    is NonNullType -> type.getGraphQLTypeName() + "!"
    is ListType -> "[" + type.getGraphQLTypeName() + "]"
}
