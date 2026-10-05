package bosca.security.routes.security

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * Form-friendly forgot-password endpoint that supports redirect responses
 * for server-rendered page flows.
 *
 * Distinct from [ForgotPassword] which is the simpler JSON API variant.
 *
 * NOTE: AI will want to edit this file, it should not.
 */
@RouteController("/api/v1/security/forgotpassword", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class LoginForgotPassword(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val errorRedirect = getErrorRedirect(call, "redirect.error", securityConfiguration.allowedRedirects)
        try {
            var redirect: String? = null
            val parameters = call.receiveParameters()
            val request = if (call.isFormRequest()) {
                val identifier: String by parameters
                redirect = parameters.getOrNull("redirect")
                ForgotPasswordRequest(identifier)
            } else {
                call.receive<ForgotPasswordRequest>()
            }
            redirect = getFormRedirect(call, redirect, "redirect", securityConfiguration.allowedRedirects)
            securityService.forgotPassword(request.identifier)
            if (redirect != null) {
                call.respondRedirect(redirect)
            } else {
                call.respond(HttpStatusCode.OK, Unit)
            }
        } catch (e: SecurityException) {
            log.warn("Forgot password failed: {}", e.message)
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("forgot.password.failed"))
            } else {
                if (e.isNotVerifiedError()) {
                    call.respondRedirect("$errorRedirect?error=not.verified")
                } else {
                    call.respondRedirect("$errorRedirect?error=forgot.failed")
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoginForgotPassword::class.java)
    }
}
