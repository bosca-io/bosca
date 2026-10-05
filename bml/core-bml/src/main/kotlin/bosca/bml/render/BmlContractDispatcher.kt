package bosca.bml.render

import bosca.bml.graphql.GraphQLClient

/**
 * A `<contract>`'s server-side dispatcher (generated — one per contract interface):
 * decodes the JSON args array POSTed to `/_bml/contract/{name}/{method}`, invokes the site's
 * implementation with the caller's GraphQL client (token passthrough, like every other data
 * access), and encodes the result back as JSON. The typed TypeScript stub on the client and this
 * dispatcher are two halves generated from the same declaration, so they can never drift.
 */
interface BmlContractDispatcher {
    /** The contract interface's name — the `{name}` route segment. */
    val name: String

    /** The callable method names — anything else on the wire is a 404, not an error. */
    val methods: List<String>

    /** Runs [method] with the JSON-array [argsJson], returning the JSON-encoded result. */
    suspend fun dispatch(gql: GraphQLClient?, method: String, argsJson: String): String
}
