package bosca.graphql.client

/** Terminal outcome observed for a GraphQL transport request. */
enum class GraphQLRequestOutcome {
    SUCCESS,
    GRAPHQL_ERROR,
    TRANSPORT_ERROR,
    CANCELLED,
}
