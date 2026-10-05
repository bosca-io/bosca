package bosca.graphql.client

/** Non-sensitive request telemetry emitted after a GraphQL request terminates. */
data class GraphQLRequestResult(
    val request: GraphQLRequest,
    val outcome: GraphQLRequestOutcome,
    val durationMillis: Long,
    val errorType: String? = null,
)
