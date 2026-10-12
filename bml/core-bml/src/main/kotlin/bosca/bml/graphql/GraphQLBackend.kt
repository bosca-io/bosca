package bosca.bml.graphql

import bosca.server.ServerCall

/** Supplies the same BML client contract regardless of where GraphQL executes. */
interface GraphQLBackend {
    /** Applies resolved BML identities to a host-owned GraphQL request before its handler runs. */
    fun prepareRequest(call: ServerCall, installationId: String?, analyticsSessionId: String?) {}

    /** Binds operations to the incoming request, its credentials, and analytics identity. */
    fun forRequest(
        call: ServerCall,
        token: String?,
        installationId: String?,
        analyticsSessionId: String?,
    ): GraphQLClient
}
