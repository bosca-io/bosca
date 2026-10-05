package bosca.graphql.client

/** Receives best-effort GraphQL request telemetry without access to documents, variables, or response data. */
fun interface GraphQLRequestObserver {
    /** Called after a request terminates. Observer failures never alter the request result. */
    fun onComplete(result: GraphQLRequestResult)
}
