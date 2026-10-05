package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * Form-friendly reset-password endpoint that supports redirect responses
 * for server-rendered page flows.
 *
 * Distinct from [ResetPassword] which is the simpler JSON API variant.
 */
@RouteController("/api/v1/security/resetpassword", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class LoginResetPassword(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    cacheManager: CacheManager,
) : Route<Unit>() {

    private val rateLimiter = AuthRateLimiter(cacheManager, maxAttempts = 10)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val errorRedirect = getErrorRedirect(call, "redirect.error", securityConfiguration.allowedRedirects)
        try {
            var redirect: String? = null
            val resetRequest = if (call.isFormRequest()) {
                val parameters = call.receiveParameters()
                val password: String by parameters
                val token: String by parameters
                redirect = parameters.getOrNull("redirect")
                ResetPasswordRequest(password, token)
            } else {
                call.receive<ResetPasswordRequest>()
            }
            redirect = getFormRedirect(call, redirect, "redirect", securityConfiguration.allowedRedirects)
            val rateLimitKey = "reset:${resetRequest.token}"
            if (rateLimiter.isRateLimited(rateLimitKey)) {
                if (errorRedirect == null) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("reset.password.rate.limited"))
                } else {
                    call.respondRedirect("$errorRedirect?error=reset.password.rate.limited")
                }
                return
            }
            rateLimiter.recordFailure(rateLimitKey)
            if (resetRequest.password.length !in PASSWORD_LENGTH_RANGE) {
                if (errorRedirect == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be between 8 and 128 characters"))
                } else {
                    call.respondRedirect("$errorRedirect?error=invalid.password")
                }
                return
            }
            securityService.resetPassword(resetRequest.token, resetRequest.password)
            if (redirect != null) {
                call.respondRedirect(redirect)
            } else {
                call.respond(HttpStatusCode.OK, Unit)
            }
        } catch (e: SecurityException) {
            log.warn("Reset password failed: {}", e.message)
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message))
            } else {
                if ("not verified" in e.message) {
                    call.respondRedirect("$errorRedirect?error=not.verified")
                } else {
                    call.respondRedirect("$errorRedirect?error=reset.failed")
                }
            }
        } catch (e: Exception) {
            log.error("failed to reset password", e)
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("reset.password.failed"))
            } else {
                call.respondRedirect("$errorRedirect?error=reset.failed")
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoginResetPassword::class.java)
    }
}
