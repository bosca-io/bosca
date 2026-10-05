package bosca.server.middleware

import bosca.server.ServerCall
import bosca.server.routing.AuthConfig

/**
 * Interface for authentication middleware that validates credentials and populates
 * the call's authentication context.
 */
interface AuthMiddleware {
    /**
     * Authenticates the request based on the provided [authConfig].
     * Populates [ServerCall.authenticationContext] on success,
     * or responds with 401 if required and authentication fails.
     */
    suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?)
}
