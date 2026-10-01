package bosca.graphql

import bosca.server.ServerCall
import kotlinx.serialization.json.JsonElement

/**
 * Authenticates a graphql-transport-ws `connection_init` payload.
 *
 * Browsers cannot set an `Authorization` header on a WebSocket
 * upgrade, so SPA clients send their token in the `connection_init`
 * payload (`connectionParams`, conventionally as `authToken`).
 * Implementations validate that token and attach the principal to the
 * upgrade call's authentication context — every subscription executed
 * on the socket afterwards reads its [bosca.security.service.AuthenticationContext]
 * from that call.
 *
 * Registered in DI by the security module's `AuthenticationModule`;
 * resolved optionally by [bosca.configuration.GraphQLModule] so
 * applications composed without the security module keep the
 * header/cookie-only behaviour of the upgrade request.
 */
interface GraphQLConnectionInitAuthenticator {

    /**
     * Validates the credentials carried in [connectionParams] for the
     * WebSocket upgrade [call].
     *
     * @return `false` to reject the connection (the protocol handler
     * closes it with 4401); `true` to proceed — including when no
     * token is present, in which case authentication falls back to
     * whatever the upgrade request itself carried (header or cookie).
     */
    suspend fun authenticate(call: ServerCall, connectionParams: JsonElement?): Boolean
}
