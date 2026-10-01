package bosca.security.routes.passkey

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.CredentialType
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class PasskeyRegisterBeginResponse(
    val stateKey: String,
    val challenge: String,
    val rp: RelyingParty,
    val user: UserEntity,
    val excludeCredentials: List<CredentialDescriptor>,
)

@Serializable
data class RelyingParty(
    val id: String,
    val name: String,
)

@Serializable
data class UserEntity(
    val id: String,
    val name: String,
    val displayName: String,
)

@Serializable
data class CredentialDescriptor(
    val id: String,
    val type: String = "public-key",
    val transports: List<String> = emptyList(),
)

/**
 * Initiates a WebAuthn registration ceremony for the authenticated principal.
 * Returns the PublicKeyCredentialCreationOptions fields needed by the browser's
 * navigator.credentials.create() call, along with a state key for correlating
 * the subsequent "complete" request.
 */
@RouteController("/api/v1/security/passkeys/register/begin", method = RouteMethod.POST, authentication = RouteAuthentication.REQUIRED)
class PasskeyRegisterBegin(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    private val stateManager: WebAuthnStateManager,
) : Route<PasskeyRegisterBeginResponse>() {

    override fun serializer(): KSerializer<PasskeyRegisterBeginResponse> = PasskeyRegisterBeginResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): PasskeyRegisterBeginResponse? {
        val authenticatedPrincipal = authenticationContext.principal() ?: run {
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("not_authenticated"))
            return null
        }
        if (authenticatedPrincipal is ScopedAuthenticatedPrincipal) {
            call.respond(HttpStatusCode.Forbidden, PasskeyErrorResponse("api_token_not_allowed"))
            return null
        }
        val principal = authenticatedPrincipal.asPrincipal()

        val webauthnConfig = securityConfiguration.webauthn
        val rpId = webauthnConfig.rpId ?: securityConfiguration.domain

        val existingPasskeys = securityService.getCredentials(principal, CredentialType.PASSKEY)
        val excludeCredentials = existingPasskeys.map { cred ->
            val attrs = cred.attributes as bosca.security.model.PasskeyCredentialAttributes
            CredentialDescriptor(
                id = attrs.identifier,
                transports = attrs.transports,
            )
        }

        val (stateKey, state) = stateManager.createChallenge(
            principalId = principal.id,
        )

        val profileName = principal.id.toString()

        return PasskeyRegisterBeginResponse(
            stateKey = stateKey,
            challenge = state.challenge,
            rp = RelyingParty(id = rpId, name = webauthnConfig.rpName),
            user = UserEntity(
                id = principal.id.toString(),
                name = profileName,
                displayName = profileName,
            ),
            excludeCredentials = excludeCredentials,
        )
    }
}
