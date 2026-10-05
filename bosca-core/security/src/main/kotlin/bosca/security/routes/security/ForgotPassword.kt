package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.routes.APIRoute
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * Initiates the forgot-password flow by sending a password reset email
 * to the account associated with the provided email address.
 *
 * Accepts the email as a query parameter and always responds with an OK status
 * regardless of whether the email exists, to prevent account enumeration.
 */
@RouteController("/api/v1/security/forgot-password", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class ForgotPassword(
    private val securityService: SecurityService,
    cacheManager: CacheManager,
) : APIRoute<Unit>() {

    private val rateLimiter = AuthRateLimiter(cacheManager, maxAttempts = 5)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val email = call.request.queryParameters["email"]?.takeIf { it.isNotBlank() }
            ?: error("missing email")
        requireValidEmail(email)
        if (rateLimiter.isRateLimited(email)) {
            log.warn("Forgot password rate limited for identifier: {}", email)
            call.respond(HttpStatusCode.TooManyRequests, mapOf("message" to "rate limited"))
            return
        }
        rateLimiter.recordFailure(email)
        try {
            securityService.forgotPassword(email, call.request.appOrigin)
        } catch (_: Exception) {
            // Suppress errors to prevent account enumeration — always return OK
        }
        call.respond(HttpStatusCode.OK, mapOf("message" to "ok"))
    }

    companion object {
        private val log = LoggerFactory.getLogger(ForgotPassword::class.java)
    }
}
