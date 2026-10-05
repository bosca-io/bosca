package bosca.security.routes.security

import bosca.graphql.codedErrorCode
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * Verifies a user's email address using a verification token, then redirects
 * to a success or error URL.
 *
 * The redirect and error redirect URLs are validated against the configured
 * allowed redirects to prevent open redirect attacks.
 */
@RouteController("/api/v1/security/verify", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class Verify(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val parameters = call.receiveParameters()
        val redirect = call.request.queryParameters["redirect"] ?: parameters["redirect"] ?: "/"
        val errorRedirect = call.request.queryParameters["redirect.error"] ?: parameters["redirect.error"] ?: "/"
        val validRedirect = validateRedirect(redirect)
        val validErrorRedirect = validateRedirect(errorRedirect)
        try {
            val token = if (call.isFormRequest()) {
                parameters.getOrNull<String>("token")?.takeIf { it.isNotBlank() }
            } else {
                call.request.queryParameters["token"]?.takeIf { it.isNotBlank() }
            } ?: error("missing token")
            securityService.verifyWithToken(token)
            call.respondRedirect(validRedirect)
        } catch (e: Exception) {
            log.error("Verification failed", e)
            // Carry a stable error code (e.g. EMAIL_ALREADY_VERIFIED) to the error page so it can show a
            // specific, actionable message instead of a generic failure. The base redirect was already
            // validated; only an appended query param is added here.
            call.respondRedirect(withErrorCode(validErrorRedirect, e.codedErrorCode()))
        }
    }

    private fun validateRedirect(redirect: String): String {
        return validateRedirect(redirect, securityConfiguration.allowedRedirects) ?: "/"
    }

    private fun withErrorCode(redirect: String, code: String?): String {
        if (code == null) return redirect
        val separator = if (redirect.contains('?')) '&' else '?'
        return "$redirect${separator}error=$code"
    }

    companion object {
        private val log = LoggerFactory.getLogger(Verify::class.java)
    }
}
