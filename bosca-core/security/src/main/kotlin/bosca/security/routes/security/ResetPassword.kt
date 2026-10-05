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
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
private data class ResetPasswordInput(val token: String, val password: String)

/**
 * Resets a user's password using a previously issued reset token.
 *
 * Accepts both JSON and form-encoded request bodies containing the reset token
 * and new password.
 */
@RouteController("/api/v1/security/reset-password", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class ResetPassword(
    private val securityService: SecurityService,
    cacheManager: CacheManager,
) : APIRoute<Unit>() {

    private val rateLimiter = AuthRateLimiter(cacheManager, maxAttempts = 5)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val isForm = call.isFormRequest()
        val input = if (isForm) {
            val parameters = call.receiveParameters()
            val token: String by parameters
            val password: String by parameters
            ResetPasswordInput(token, password)
        } else {
            call.receive<ResetPasswordInput>()
        }
        val rateLimitKey = "reset:${input.token}"
        if (rateLimiter.isRateLimited(rateLimitKey)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("reset.password.rate.limited"))
            return
        }
        if (input.password.length !in PASSWORD_LENGTH_RANGE) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be between 8 and 128 characters"))
            return
        }
        try {
            rateLimiter.recordFailure(rateLimitKey)
            securityService.resetPassword(input.token, input.password)
            rateLimiter.recordSuccess(rateLimitKey)
            call.respond(HttpStatusCode.OK, mapOf("message" to "ok"))
        } catch (e: bosca.security.service.SecurityException) {
            log.warn("Reset password failed: {}", e.message)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("reset.password.failed"))
        } catch (e: Exception) {
            log.error("Reset password error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("reset.password.failed"))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ResetPassword::class.java)
    }
}
