package bosca.ai.kit.tools.graphql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.graphql.GraphQLService
import bosca.graphql.printer.GraphQLPrinter
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Returns the SDL definition for a single GraphQL type (fields and arguments). */
class GraphQLGetTypeDefinitionTool(
    private val graphQLService: GraphQLService,
) : KitTool<GraphQLGetTypeDefinitionTool.Input, GraphQLGetTypeDefinitionTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "graphql_get_type_definition",
    description = "Fetches the SDL definition for a specific GraphQL type including its fields and arguments",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The name of the GraphQL type to fetch (e.g. 'Content', 'MetadataInput')")
        val typeName: String,
    )

    @Serializable
    data class Output(
        val sdl: String,
        val found: Boolean,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val schema = graphQLService.getSchema()
        val type = schema.type(input.typeName)
        return if (type == null) {
            Output(sdl = "Type '${input.typeName}' not found in schema.", found = false)
        } else {
            Output(sdl = GraphQLPrinter.print(type), found = true)
        }
    }
}
