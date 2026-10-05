package bosca.security.routes.passkey

import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.CredentialType
import bosca.security.model.PasskeyCredentialAttributes
import bosca.security.model.PrincipalCredential
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.session.Session
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64

@Serializable
data class PasskeyAuthenticateCompleteRequest(
    val stateKey: String,
    val credentialId: String,
    val authenticatorData: String,
    val clientDataJSON: String,
    val signature: String,
    val userHandle: String? = null,
)

@Serializable
data class PasskeyAuthenticateCompleteResponse(
    val token: String,
    val expiresAt: Int,
    val issuedAt: Int,
    val refreshToken: String? = null,
)

/**
 * Completes a WebAuthn authentication ceremony by validating the assertion response
 * from the authenticator against the stored public key credential. On success,
 * establishes a session and returns JWT tokens.
 *
 * Applies IP-based rate limiting (10 requests per minute per IP) to mitigate
 * brute-force assertion replay attacks against this unauthenticated endpoint.
 */
@RouteController("/api/v1/security/passkeys/authenticate/complete", method = RouteMethod.POST, authentication = RouteAuthentication.NONE)
class PasskeyAuthenticateComplete(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
    private val stateManager: WebAuthnStateManager,
) : Route<PasskeyAuthenticateCompleteResponse>() {

    override fun serializer(): KSerializer<PasskeyAuthenticateCompleteResponse> = PasskeyAuthenticateCompleteResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): PasskeyAuthenticateCompleteResponse? {
        val clientIp = call.request.clientIp ?: "unknown"
        if (rateLimiter.isRateLimited(clientIp)) {
            log.warn("WebAuthn authenticate/complete rate limited for IP {}", clientIp)
            call.respond(HttpStatusCode.TooManyRequests, PasskeyErrorResponse("rate_limited"))
            return null
        }

        val request = call.receive<PasskeyAuthenticateCompleteRequest>()

        val challengeState = stateManager.retrieveAndRemoveChallenge(request.stateKey) ?: run {
            log.warn("WebAuthn authentication: challenge state not found or expired")
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("challenge_expired"))
            return null
        }

        val base64UrlDecoder = Base64.getUrlDecoder()
        val credentialIdBytes = try {
            base64UrlDecoder.decode(request.credentialId)
        } catch (_: IllegalArgumentException) {
            log.warn("WebAuthn authentication: invalid base64url in credentialId")
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("invalid_encoding"))
            return null
        }

        val principalId = if (request.userHandle != null) {
            try {
                kotlin.uuid.Uuid.parse(request.userHandle)
            } catch (_: IllegalArgumentException) {
                log.warn("WebAuthn authentication: invalid userHandle format")
                call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("invalid_user_handle"))
                return null
            }
        } else {
            log.warn("WebAuthn authentication: no userHandle provided — non-discoverable credentials not supported")
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("missing_user_handle"))
            return null
        }

        val credentials = securityService.getCredentials(
            securityService.getPrincipalById(principalId) ?: run {
                log.warn("WebAuthn authentication: principal not found for id {}", principalId)
                call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("principal_not_found"))
                return null
            },
            CredentialType.PASSKEY
        )

        val matchingCredential: PrincipalCredential = credentials.firstOrNull {
            it.attributes.identifier == request.credentialId
        } ?: run {
            log.warn("WebAuthn authentication: credential not found for id {}", request.credentialId)
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("credential_not_found"))
            return null
        }

        val attrs = matchingCredential.attributes as PasskeyCredentialAttributes
        val publicKeyCoseBytes = base64UrlDecoder.decode(attrs.publicKeyCose)

        val webauthnConfig = securityConfiguration.webauthn
        val rpId = webauthnConfig.rpId ?: securityConfiguration.domain
        val origins = webauthnConfig.allowedOrigins(rpId, securityConfiguration.adminDomain)

        val authenticatorDataBytes: ByteArray
        val clientDataJSONBytes: ByteArray
        val signatureBytes: ByteArray
        try {
            authenticatorDataBytes = base64UrlDecoder.decode(request.authenticatorData)
            clientDataJSONBytes = base64UrlDecoder.decode(request.clientDataJSON)
            signatureBytes = base64UrlDecoder.decode(request.signature)
        } catch (_: IllegalArgumentException) {
            log.warn("WebAuthn authentication: invalid base64url in assertion fields")
            call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("invalid_encoding"))
            return null
        }

        val authenticationRequest = AuthenticationRequest(
            credentialIdBytes,
            authenticatorDataBytes,
            clientDataJSONBytes,
            signatureBytes,
        )

        val serverProperty = ServerProperty(
            origins.map { Origin.create(it) }.toSet(),
            rpId,
            DefaultChallenge(base64UrlDecoder.decode(challengeState.challenge)),
            null,
        )

        val cborConverter = WebAuthnManagerFactory.objectConverter.cborConverter
        val coseKey = cborConverter.readValue(publicKeyCoseBytes, COSEKey::class.java)
            ?: run {
                log.error("WebAuthn authentication: failed to deserialize COSE key")
                call.respond(HttpStatusCode.BadRequest, PasskeyErrorResponse("invalid_credential_key"))
                return null
            }
        val authenticator = AuthenticatorImpl(
            AttestedCredentialData(
                AAGUID.NULL,
                credentialIdBytes,
                coseKey,
            ),
            null,
            attrs.signCount,
        )

        val authenticationParameters = AuthenticationParameters(
            serverProperty,
            authenticator,
            listOf(credentialIdBytes),
            true,
        )

        val authenticationData = try {
            WebAuthnManagerFactory.webAuthnManager.verify(authenticationRequest, authenticationParameters)
        } catch (e: com.webauthn4j.verifier.exception.VerificationException) {
            log.error("WebAuthn authentication validation failed", e)
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("verification_failed"))
            return null
        }

        val newSignCount = authenticationData.authenticatorData?.signCount ?: 0L
        val storedSignCount = attrs.signCount
        if (isSignCountRegression(storedSignCount, newSignCount)) {
            log.error("WebAuthn sign count regression detected (stored={}, reported={}), possible credential cloning", storedSignCount, newSignCount)
            call.respond(HttpStatusCode.Unauthorized, PasskeyErrorResponse("credential_compromised"))
            return null
        }
        if (newSignCount > storedSignCount) {
            securityService.updatePasskeySignCount(matchingCredential.id, newSignCount)
        }

        val loginResponse = securityService.loginWithPasskey(
            principalId,
            request.credentialId,
            generateRefreshToken = true,
        )

        call.sessions.clear()
        val groups = securityService.getPrincipalGroups(principalId)
        call.sessions.set(Session(loginResponse, groups.any { it.name == "administrators" }))

        return PasskeyAuthenticateCompleteResponse(
            token = loginResponse.token.token,
            expiresAt = loginResponse.token.expiresAt,
            issuedAt = loginResponse.token.issuedAt,
            refreshToken = loginResponse.refreshToken,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PasskeyAuthenticateComplete::class.java)
        private val rateLimiter = IpRateLimiter(maxRequests = 10, windowSeconds = 60)

        /**
         * Determines whether a WebAuthn sign-count regression has occurred, indicating
         * possible credential cloning. A regression is detected when both the stored
         * and reported sign counts are positive and the new count does not exceed the
         * stored count. When the stored count is zero the authenticator is assumed not
         * to support sign-count tracking, so any reported value is accepted.
         */
        internal fun isSignCountRegression(storedSignCount: Long, newSignCount: Long): Boolean {
            return storedSignCount > 0 && newSignCount > 0 && newSignCount <= storedSignCount
        }
    }
}
