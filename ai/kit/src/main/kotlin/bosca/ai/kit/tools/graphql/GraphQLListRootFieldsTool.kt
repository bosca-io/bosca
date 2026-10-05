package bosca.ai.kit.tools.graphql

import bosca.ai.kit.tools.KitTool
import bosca.graphql.GraphQLService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Lists the Query and Mutation root fields available on the platform GraphQL schema. */
class GraphQLListRootFieldsTool(
    private val graphQLService: GraphQLService,
) : KitTool<GraphQLListRootFieldsTool.Args, GraphQLListRootFieldsTool.Output>(
    Args.serializer(),
    Output.serializer(),
    name = "graphql_list_root_fields",
    description = "Returns a list of available Query and Mutation root fields from the GraphQL schema",
) {

    @Serializable
    class Args

    @Serializable
    data class Output(
        val queryFields: String,
        val mutationFields: String,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Args): Output {
        val schema = graphQLService.getSchema()
        val queryFields = schema.queryType?.fields?.joinToString("\n") {
            "- query.${it.name}: ${it.type.getGraphQLTypeName()} - ${it.description ?: "No description"}"
        } ?: ""
        val mutationFields = schema.mutationType?.fields?.joinToString("\n") {
            "- mutation.${it.name}: ${it.type.getGraphQLTypeName()} - ${it.description ?: "No description"}"
        } ?: ""
        return Output(queryFields = queryFields, mutationFields = mutationFields)
    }
}
