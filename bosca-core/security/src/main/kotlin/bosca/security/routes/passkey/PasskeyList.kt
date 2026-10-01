package bosca.security.routes.passkey

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.CredentialType
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class PasskeyInfo(
    val credentialId: String,
    val name: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val transports: List<String>,
)

/**
 * Returns the list of registered passkeys for the authenticated principal,
 * providing metadata needed for the account settings passkey management UI.
 */
@RouteController("/api/v1/security/passkeys", method = RouteMethod.GET, authentication = RouteAuthentication.REQUIRED)
class PasskeyList(
    private val securityService: SecurityService,
) : Route<List<PasskeyInfo>>() {

    override fun serializer(): KSerializer<List<PasskeyInfo>> = ListSerializer(PasskeyInfo.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<PasskeyInfo>? {
        val authenticatedPrincipal = authenticationContext.principal() ?: run {
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("not_authenticated"))
            return null
        }

        val credentials = securityService.getCredentials(authenticatedPrincipal.asPrincipal(), CredentialType.PASSKEY)
        return credentials.map { cred ->
            val attrs = cred.attributes as PasskeyCredentialAttributes
            PasskeyInfo(
                credentialId = attrs.identifier,
                name = attrs.name,
                createdAt = attrs.createdAt,
                lastUsedAt = attrs.lastUsedAt,
                transports = attrs.transports,
            )
        }
    }
}
