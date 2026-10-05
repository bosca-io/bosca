package bosca.security.routes.passkey

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.CredentialType
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import com.webauthn4j.data.*
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class PasskeyRegisterCompleteRequest(
    val stateKey: String,
    val name: String,
    val credentialId: String,
    val attestationObject: String,
    val clientDataJSON: String,
    val transports: List<String> = emptyList(),
)

@Serializable
data class PasskeyRegisterCompleteResponse(
    val credentialId: String,
    val name: String,
)

/**
 * Completes a WebAuthn registration ceremony by validating the attestation response
 * from the authenticator and persisting the public key credential for the authenticated
 * principal.
 */
@RouteController("/api/v1/security/passkeys/register/complete", method = RouteMethod.POST, authentication = RouteAuthentication.REQUIRED)
class PasskeyRegisterComplete(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    private val stateManager: WebAuthnStateManager,
) : Route<PasskeyRegisterCompleteResponse>() {

    override fun serializer(): KSerializer<PasskeyRegisterCompleteResponse> = PasskeyRegisterCompleteResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): PasskeyRegisterCompleteResponse? {
        val authenticatedPrincipal = authenticationContext.principal() ?: run {
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("not_authenticated"))
            return null
        }
        if (authenticatedPrincipal is ScopedAuthenticatedPrincipal) {
            call.respond(HttpStatusCode.Forbidden, PasskeyErrorResponse("api_token_not_allowed"))
            return null
        }
        val principal = authenticatedPrincipal.asPrincipal()

        val request = call.receive<PasskeyRegisterCompleteRequest>()

        val trimmedName = request.name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > 255) {
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("invalid_name"))
            return null
        }

        val challengeState = stateManager.retrieveAndRemoveChallenge(request.stateKey) ?: run {
            log.warn("WebAuthn registration: challenge state not found or expired for principal {}", principal.id)
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("challenge_expired"))
            return null
        }

        if (challengeState.principalId != principal.id) {
            log.error("WebAuthn registration: principal mismatch — state={}, request={}", challengeState.principalId, principal.id)
            call.respond(HttpStatusCode.Forbidden, PasskeyErrorResponse("principal_mismatch"))
            return null
        }

        val webauthnConfig = securityConfiguration.webauthn
        val rpId = webauthnConfig.rpId ?: securityConfiguration.domain
        val origins = webauthnConfig.allowedOrigins(rpId, securityConfiguration.adminDomain)

        val base64UrlDecoder = Base64.getUrlDecoder()
        val attestationObjectBytes: ByteArray
        val clientDataJSONBytes: ByteArray
        try {
            attestationObjectBytes = base64UrlDecoder.decode(request.attestationObject)
            clientDataJSONBytes = base64UrlDecoder.decode(request.clientDataJSON)
        } catch (_: IllegalArgumentException) {
            log.warn("WebAuthn registration: invalid base64url in attestation fields for principal {}", principal.id)
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("invalid_encoding"))
            return null
        }

        val registrationRequest = RegistrationRequest(attestationObjectBytes, clientDataJSONBytes)
        val serverProperty = ServerProperty(
            origins.map { Origin.create(it) }.toSet(),
            rpId,
            DefaultChallenge(base64UrlDecoder.decode(challengeState.challenge)),
            null,
        )
        val pubKeyCredParams = listOf(
            PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256),
        )
        val registrationParameters = RegistrationParameters(serverProperty, pubKeyCredParams, false)

        val registrationData = try {
            WebAuthnManagerFactory.webAuthnManager.verify(registrationRequest, registrationParameters)
        } catch (e: com.webauthn4j.verifier.exception.VerificationException) {
            log.error("WebAuthn registration validation failed for principal {}", principal.id, e)
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("verification_failed"))
            return null
        }

        val authenticatorData = registrationData.attestationObject?.authenticatorData ?: run {
            log.error("WebAuthn registration: no attestation object for principal {}", principal.id)
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("missing_credential_data"))
            return null
        }
        val attestedCredentialData = authenticatorData.attestedCredentialData ?: run {
                log.error("WebAuthn registration: no attested credential data for principal {}", principal.id)
                call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("missing_credential_data"))
                return null
            }

        val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
        val credentialId = base64UrlEncoder.encodeToString(attestedCredentialData.credentialId)
        val cborConverter = WebAuthnManagerFactory.objectConverter.cborConverter
        val publicKeyCose = base64UrlEncoder.encodeToString(
            cborConverter.writeValueAsBytes(attestedCredentialData.coseKey)
        )
        val aaguid = attestedCredentialData.aaguid.value?.toString()
        val signCount = authenticatorData.signCount

        val attributes = PasskeyCredentialAttributes(
            identifier = credentialId,
            name = trimmedName,
            publicKeyCose = publicKeyCose,
            signCount = signCount,
            aaguid = aaguid,
            transports = request.transports,
            createdAt = Instant.now().toString(),
        )

        val existingCredentials = securityService.getCredentials(
            securityService.getPrincipalById(principal.id)!!,
            CredentialType.PASSKEY
        )
        if (existingCredentials.any { it.attributes.identifier == credentialId }) {
            log.warn("WebAuthn registration: duplicate credential ID {} for principal {}", credentialId, principal.id)
            call.respond(HttpStatusCode.Conflict, PasskeyErrorResponse("duplicate_credential"))
            return null
        }

        securityService.addPasskeyCredential(principal.id, attributes)

        return PasskeyRegisterCompleteResponse(
            credentialId = credentialId,
            name = trimmedName,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PasskeyRegisterComplete::class.java)
    }
}
