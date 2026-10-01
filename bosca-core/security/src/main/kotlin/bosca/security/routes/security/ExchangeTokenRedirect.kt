package bosca.security.routes.security

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import java.net.URLEncoder

/**
 * Hands an authenticated browser session to another allow-listed Bosca surface.
 *
 * The server owns the redirect so the single-use exchange token can only be
 * delivered to an origin already trusted by the OAuth redirect configuration.
 * API token authentication cannot initiate this browser handoff.
 */
@RouteController(
    path = "/api/v1/security/exchange-token",
    method = RouteMethod.GET,
    authentication = RouteAuthentication.REQUIRED,
)
class ExchangeTokenRedirect(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
) : Route<Unit>() {

    override suspend fun execute(
        call: ServerCall,
        authenticationContext: AuthenticationContext,
    ) {
        val redirect = call.request.queryParameters["redirect"]
        if (redirect.isNullOrBlank() ||
            validateRedirect(redirect, securityConfiguration.allowedRedirects) != redirect
        ) {
            call.respond(HttpStatusCode.BadRequest, "redirect not allowed")
            return
        }

        val authenticatedPrincipal = authenticationContext.principal()
            ?: throw SecurityException("principal not found")
        if (authenticatedPrincipal is ScopedAuthenticatedPrincipal) {
            call.respond(HttpStatusCode.Forbidden, "API tokens cannot create interactive sessions")
            return
        }
        val principalId = authenticatedPrincipal.id
        val exchangeToken = securityService.createExchangeToken(principalId)
        val separator = if ('?' in redirect) '&' else '?'
        val encodedToken = URLEncoder.encode(exchangeToken, Charsets.UTF_8)

        call.response.header("Cache-Control", "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        call.respondRedirect("${redirect}${separator}exchangeToken=$encodedToken")
    }
}
