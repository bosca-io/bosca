package bosca.ai.kit.tools.graphql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.graphql.GraphQLService
import bosca.graphql.language.InterfaceTypeDefinition
import bosca.graphql.language.ObjectTypeDefinition
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Keyword search across GraphQL type names, descriptions, and field names. */
class GraphQLSearchSchemaTool(
    private val graphQLService: GraphQLService,
) : KitTool<GraphQLSearchSchemaTool.Input, GraphQLSearchSchemaTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "graphql_search_schema",
    description = "Keyword search across GraphQL type names, descriptions, and field names",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The keyword to search for in type names, field names, and descriptions")
        val query: String,
    )

    @Serializable
    data class Output(
        val results: List<String>,
        val count: Int,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val schema = graphQLService.getSchema()
        val results = mutableListOf<String>()
        val keyword = input.query

        schema.types.values.forEach { type ->
            if (type.name.contains(keyword, ignoreCase = true) ||
                type.description?.contains(keyword, ignoreCase = true) == true
            ) {
                results.add("Type: ${type.name}")
            }
            val fields = when (type) {
                is ObjectTypeDefinition -> type.fields
                is InterfaceTypeDefinition -> type.fields
                else -> emptyList()
            }
            fields.forEach { field ->
                    if (field.name.contains(keyword, ignoreCase = true) ||
                        field.description?.contains(keyword, ignoreCase = true) == true
                    ) {
                        results.add("Field: ${type.name}.${field.name} (${field.type.getGraphQLTypeName()})")
                    }
            }
        }

        return Output(results = results, count = results.size)
    }
}
