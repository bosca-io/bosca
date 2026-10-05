package bosca.security.graphql

import bosca.cache.CacheManager
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.LoginResponse
import bosca.security.model.SimplePasswordAttributes
import bosca.security.routes.security.AuthRateLimiter
import bosca.security.routes.security.PASSWORD_LENGTH_RANGE
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.server.ServerCall
import kotlinx.coroutines.CancellationException

object LoginMutation

@TypeController
class LoginMutationController(
    private val securityService: SecurityService,
    cacheManager: CacheManager
) : GraphQLController<LoginMutation> {

    private val rateLimiter = AuthRateLimiter(cacheManager)

    @Field
    suspend fun forgotPassword(identifier: String, call: ServerCall): Boolean {
        if (rateLimiter.isRateLimited(identifier)) {
            throw SecurityException("authentication.rate.limited")
        }
        rateLimiter.recordFailure(identifier)
        // Carry the host the user is on so the reset email routes back to it (multi-host). appOrigin reads
        // the browser Origin/Referer, not the addressed API host, so a proxied call still names the app host.
        securityService.forgotPassword(identifier, call.request.appOrigin)
        return true
    }

    @Field
    suspend fun resetPassword(token: String, password: String): Boolean {
        require(password.length in PASSWORD_LENGTH_RANGE) { "Password must be between ${PASSWORD_LENGTH_RANGE.first} and ${PASSWORD_LENGTH_RANGE.last} characters" }
        securityService.resetPassword(token, password)
        return true
    }

    @Field
    suspend fun password(
        identifier: String,
        password: String,
        originator: String?
    ): LoginResponse {
        if (rateLimiter.isRateLimited(identifier)) {
            throw SecurityException("authentication.rate.limited")
        }
        return try {
            // Pass the originator through so it is echoed back AND recorded as the credential's last originator.
            val response = securityService.loginWithCredential(SimplePasswordAttributes(identifier, password), generateRefreshToken = true, originator = originator)
            rateLimiter.recordSuccess(identifier)
            response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rateLimiter.recordFailure(identifier)
            throw e
        }
    }

    @Field
    suspend fun refreshToken(refreshToken: String): LoginResponse {
        return securityService.loginWithRefreshToken(refreshToken)
    }

    @Field
    suspend fun exchangeToken(token: String): LoginResponse {
        return securityService.loginWithExchangeToken(token)
    }

    /**
     * Terminates only the current user's authenticated sign-in:
     *
     *   1. If the request carries a tracked login ID, revokes that
     *      login and deletes its refresh token. Other sign-ins for
     *      the same principal remain valid.
     *
     *   2. Unconditionally clears the HTTP-only `_bat` session
     *      cookie on both the primary and admin domains via
     *      `call.sessions.clear()`. The `SessionMiddleware` picks
     *      up the modification and `BoscaAuthMiddleware.clearSessionCookie`
     *      emits a maxAge=0 Set-Cookie. This is the **only** way
     *      to remove an HTTP-only cookie — the browser will not
     *      let JavaScript delete it, so a client-side-only sign-out
     *      cannot clean up after itself.
     *
     * The cookie clear runs even when step 1 is skipped (already
     * signed out, JWT already invalidated, no authentication
     * context at all) so calling `signOut` on a stale session
     * always returns the client to a clean state.
     */
    @Field
    suspend fun signOut(authentication: AuthenticationContext?, call: ServerCall): Boolean {
        val principal = authentication?.principal()
        if (principal != null) {
            securityService.signOut(principal.id, principal.loginId)
        }
        // Always clear the transport-layer cookie, even when the
        // server-side invalidation was skipped. A client calling
        // `signOut` with a dead JWT still needs its cookie wiped.
        call.sessions.clear()
        return true
    }
}
