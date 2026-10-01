package bosca.graphql.client

/** Safe identity for one GraphQL request; the query document and variables are intentionally excluded. */
data class GraphQLRequest(
    val operationName: String?,
)
