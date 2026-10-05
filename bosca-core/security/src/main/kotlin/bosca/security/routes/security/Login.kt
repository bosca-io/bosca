package bosca.security.routes.security

import bosca.cache.CacheManager
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.session.Session
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import org.slf4j.LoggerFactory

/**
 * Authenticates a user with email/password credentials and establishes a session.
 *
 * Supports both JSON and form-encoded request bodies. Form requests may include
 * redirect parameters for server-rendered page flows. JSON requests receive
 * the login response token directly.
 */
@RouteController("/api/v1/security/login", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class Login(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    cacheManager: CacheManager,
) : Route<Unit>() {

    private val rateLimiter = AuthRateLimiter(cacheManager)

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val errorRedirect = getErrorRedirect(call, "redirect.error", securityConfiguration.allowedRedirects)
        var identifier: String? = null
        try {
            var formRedirect: String? = null
            val isForm = call.isFormRequest()
            val loginRequest = if (isForm) {
                val parameters = call.receiveParameters()
                val formIdentifier: String = parameters["identifier"] ?: error("Missing identifier parameter")
                val password: String by parameters
                formRedirect = parameters.getOrNull("redirect")
                LoginRequest(formIdentifier, password)
            } else {
                call.receive<LoginRequest>()
            }
            identifier = loginRequest.identifier
            if (rateLimiter.isRateLimited(identifier)) {
                log.warn("Login rate limited for identifier: {}", identifier)
                if (errorRedirect == null) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("authentication.rate.limited"))
                } else {
                    call.respondRedirect("$errorRedirect?error=authentication.rate.limited")
                }
                return
            }
            val redirect = getFormRedirect(call, formRedirect, "redirect", securityConfiguration.allowedRedirects)
            val signupTokens = call.getSignUpTokens()
            val adminRequested = call.request.queryParameters["admin"] == "true"
            val loginResponse = securityService.loginWithCredential(
                SimplePasswordAttributes(loginRequest.identifier, loginRequest.password),
                generateRefreshToken = true,
                signupTokens = signupTokens,
            )
            rateLimiter.recordSuccess(identifier)
            // Clear any existing session before setting the new one to prevent session fixation
            call.sessions.clear()
            if (adminRequested) {
                log.info("Setting admin session for principal: ${loginResponse.principalId}")
                val groups = securityService.getPrincipalGroups(loginResponse.principalId)
                call.sessions.set(Session(loginResponse, groups.any { it.name == "administrators" }))
            } else {
                log.info("Setting session for principal: ${loginResponse.principalId}")
                call.sessions.set(Session(loginResponse, false))
            }
            if (redirect != null) {
                call.respondRedirect(redirect)
            } else if (!isForm) {
                call.respond(HttpStatusCode.OK, loginResponse)
            } else {
                call.respond(HttpStatusCode.OK, Unit)
            }
        } catch (e: bosca.security.service.SecurityException) {
            log.warn("Login failed: {}", e.message)
            identifier?.let { rateLimiter.recordFailure(it) }
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication.failed"))
            } else {
                if (e.isNotVerifiedError()) {
                    call.respondRedirect("$errorRedirect?error=not.verified")
                } else {
                    call.respondRedirect("$errorRedirect?error=authentication.failed")
                }
            }
        } catch (e: SecurityException) {
            log.warn("Login failed: {}", e.message)
            identifier?.let { rateLimiter.recordFailure(it) }
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication.failed"))
            } else {
                if (e.isNotVerifiedError()) {
                    call.respondRedirect("$errorRedirect?error=not.verified")
                } else {
                    call.respondRedirect("$errorRedirect?error=authentication.failed")
                }
            }
        } catch (e: Exception) {
            log.error("failed to login", e)
            if (errorRedirect == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication.failed"))
            } else {
                call.respondRedirect("$errorRedirect?error=authentication.failed")
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Login::class.java)
    }
}
