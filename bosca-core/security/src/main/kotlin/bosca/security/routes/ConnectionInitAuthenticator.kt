package bosca.security.routes

import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.server.ServerCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Authenticates graphql-transport-ws `connection_init` payloads.
 *
 * Browser clients (the studio's subscription composables) cannot set
 * an `Authorization` header on a WebSocket upgrade, so they pass their
 * token as `authToken` in the connection params. The token validates
 * through the same bearer path as the `Authorization` header —
 * `bsk_` API tokens and JWTs both work — and the resulting principal
 * is attached to the upgrade call, which every subscription on the
 * socket reads its authentication context from.
 *
 * A payload without a token is allowed through: the upgrade request
 * may already have authenticated via header or session cookie, and
 * subscription resolvers enforce their own authorization. A present
 * but invalid token rejects the connection (closed with 4401).
 */
class ConnectionInitAuthenticator(
    private val authMiddleware: BoscaAuthMiddleware,
) : GraphQLConnectionInitAuthenticator {

    override suspend fun authenticate(call: ServerCall, connectionParams: JsonElement?): Boolean {
        val token = ((connectionParams as? JsonObject)?.get("authToken") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        if (token.isNullOrBlank()) return true
        return authMiddleware.authenticateBearerToken(call, token)
    }
}
