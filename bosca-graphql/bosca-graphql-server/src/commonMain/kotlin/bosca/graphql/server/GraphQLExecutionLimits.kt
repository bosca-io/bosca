package bosca.graphql.server

/**
 * Runtime response-work limits for one GraphQL execution.
 *
 * Static query complexity cannot know the number of elements returned by a resolver, so these limits bound actual
 * response cardinality and coroutine fan-out as values are completed.
 */
data class GraphQLExecutionLimits(
    val maxListItems: Int = 20_000,
    val maxConcurrentFields: Int = 128,
    val maxConcurrentListItems: Int = 32,
    val maxResponseNodes: Int = 200_000,
) {
    init {
        require(maxListItems > 0) { "maxListItems must be positive" }
        require(maxConcurrentFields > 0) { "maxConcurrentFields must be positive" }
        require(maxConcurrentListItems > 0) { "maxConcurrentListItems must be positive" }
        require(maxResponseNodes > 0) { "maxResponseNodes must be positive" }
    }

    companion object {
        val DEFAULT = GraphQLExecutionLimits()
    }
}
