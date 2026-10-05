package bosca.security.routes.passkey

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.CredentialType
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class PasskeyDeleteRequest(
    val credentialId: String,
)

/**
 * Removes a registered passkey from the authenticated principal's account.
 * Prevents removal if it would leave the principal with no credentials at all.
 */
@RouteController("/api/v1/security/passkeys/delete", method = RouteMethod.POST, authentication = RouteAuthentication.REQUIRED)
class PasskeyDelete(
    private val securityService: SecurityService,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val authenticatedPrincipal = authenticationContext.principal() ?: run {
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("not_authenticated"))
            return
        }
        if (authenticatedPrincipal is ScopedAuthenticatedPrincipal) {
            call.respond(HttpStatusCode.Forbidden, PasskeyErrorResponse("api_token_not_allowed"))
            return
        }
        val principal = authenticatedPrincipal.asPrincipal()

        val request = call.receive<PasskeyDeleteRequest>()
        securityService.deleteCredential(principal.id, CredentialType.PASSKEY, request.credentialId)
        log.info("WebAuthn passkey deleted: principal={}, credentialId={}", principal.id, request.credentialId)
        call.respond(HttpStatusCode.NoContent, "")
    }

    companion object {
        private val log = LoggerFactory.getLogger(PasskeyDelete::class.java)
    }
}
