package bosca.ai.kit.agents.graphql

import bosca.graphql.GraphQLService
import bosca.graphql.printer.GraphQLPrinter

/**
 * Resolves the SDL definition for the GraphQL [type] of a result (the "what is this?" reference) **from
 * the live schema** — authoritative, not a model echo. [type] may carry SDL wrappers (`Metadata`,
 * `[Collection]`, `Metadata!`, `[Metadata!]!`); they're stripped to the base type name and its
 * definition is printed. Returns "" when the type is empty or not found (e.g. a scalar, or an
 * introspection-only answer with no data type).
 */
internal suspend fun graphQLTypeSdl(graphQLService: GraphQLService, type: String): String {
    val baseName = type.trim().trim('[', ']', '!').trim()
    if (baseName.isEmpty()) return ""
    val gqlType = graphQLService.getSchema().type(baseName) ?: return ""
    return GraphQLPrinter.print(gqlType)
}
