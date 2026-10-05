package bosca.ai.kit.tools.graphql

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.graphql.GraphQLRequest
import bosca.graphql.GraphQLService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Executes a GraphQL query or mutation against the Bosca platform **as the calling user** — the same API
 * the UI uses, so resolver permission checks decide what the agent can read or change. The [Output.data]
 * is the verbatim GraphQL response (so the model can answer from the real rows, and the agent can carry
 * the exact result through to its response).
 */
class GraphQLQueryTool(
    private val graphQLService: GraphQLService,
    private val json: Json,
) : KitTool<GraphQLQueryTool.Input, GraphQLQueryTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "graphql_query",
    description = "Execute a GraphQL query or mutation against the Bosca platform as the current user. " +
        "Use the graphql_* schema-discovery tools first to learn the available fields, types, and arguments. " +
        "Returns the GraphQL response ('data' and/or 'errors'); read it and answer the user from it. " +
        "Mutations create/edit/delete content and are permission-checked as the user.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The GraphQL query or mutation document.")
        val query: String,
        @property:LLMDescription("A JSON object string of GraphQL variables, or empty if the operation takes none.")
        val variables: String = "",
    )

    @Serializable
    data class Output(
        @property:LLMDescription("The GraphQL response JSON ('data' and/or 'errors').")
        val data: JsonElement,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        return try {
            val variables: JsonObject? = if (input.variables.isNotBlank()) {
                json.decodeFromString(JsonObject.serializer(), input.variables)
            } else {
                null
            }
            val result = graphQLService.execute(authentication, GraphQLRequest(query = input.query, variables = variables))
            Output(data = result, success = true)
        } catch (e: Exception) {
            Output(data = JsonNull, success = false, error = e.message)
        }
    }
}
