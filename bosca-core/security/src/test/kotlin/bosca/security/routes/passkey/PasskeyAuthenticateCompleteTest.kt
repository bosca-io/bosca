package bosca.security.routes.passkey

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.LoginResponse
import bosca.security.model.Principal
import bosca.security.model.Token
import bosca.security.routes.security.SecurityRouteTestCall
import bosca.security.routes.security.SecurityRouteTestEnvironment
import bosca.security.routes.security.authRateLimitCacheManager
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.WebAuthnConfiguration
import bosca.security.session.Session
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.CborConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
import com.webauthn4j.verifier.exception.BadSignatureException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class PasskeyAuthenticateCompleteTest {

    private val securityService = mockk<SecurityService>()
    private val stateManager = mockk<WebAuthnStateManager>()
    private val configuration = mockk<SecurityConfiguration> {
        every { domain } returns "example.com"
        every { adminDomain } returns "admin.example.com"
        every { authCookiePrefixes } returns emptyList()
        every { webauthn } returns WebAuthnConfiguration()
    }
    private lateinit var environment: SecurityRouteTestEnvironment

    @BeforeTest
    fun setUp() {
        environment = SecurityRouteTestEnvironment(authRateLimitCacheManager())
        mockkObject(WebAuthnManagerFactory)
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(WebAuthnManagerFactory)
        environment.close()
    }

    @Test
    fun `expired challenge is rejected`() = runTest {
        val testCall = requestCall(clientIp = "192.0.2.101")
        coEvery { stateManager.retrieveAndRemoveChallenge("state") } returns null

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "challenge_expired")
    }

    @Test
    fun `invalid credential encoding is rejected`() = runTest {
        val testCall = requestCall(
            credentialId = "%",
            clientIp = "192.0.2.102",
        )
        validState()

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "invalid_encoding")
    }

    @Test
    fun `missing and malformed user handles are rejected`() = runTest {
        val route = route()
        validState()
        val missing = requestCall(userHandle = null, clientIp = "192.0.2.103")
        val malformed = requestCall(userHandle = "not-a-uuid", clientIp = "192.0.2.104")

        route.execute(missing.call)
        route.execute(malformed.call)

        verifyError(missing, HttpStatusCode.BadRequest, "missing_user_handle")
        verifyError(malformed, HttpStatusCode.BadRequest, "invalid_user_handle")
    }

    @Test
    fun `unknown principal is rejected`() = runTest {
        val principalId = UUID.random()
        val testCall = requestCall(
            userHandle = principalId.toString(),
            clientIp = "192.0.2.105",
        )
        validState()
        coEvery { securityService.getPrincipalById(principalId) } returns null

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.Unauthorized, "principal_not_found")
    }

    @Test
    fun `unknown credential is rejected`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            clientIp = "192.0.2.106",
        )
        validState()
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery { securityService.getCredentials(any(), any()) } returns emptyList()

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.Unauthorized, "credential_not_found")
    }

    @Test
    fun `invalid assertion field encoding is rejected`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            authenticatorData = "%",
            clientIp = "192.0.2.107",
        )
        validCredential(principal)

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "invalid_encoding")
    }

    @Test
    fun `WebAuthn verification failures are unauthorized`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            clientIp = "192.0.2.108",
        )
        validCredential(principal)
        stubWebAuthnVerification(error = BadSignatureException("bad signature"))

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.Unauthorized, "verification_failed")
    }

    @Test
    fun `invalid COSE key is rejected with explicit relying party configuration and unknown address`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            clientIp = null,
        )
        validCredential(principal)
        every {
            configuration.webauthn
        } returns WebAuthnConfiguration(
            rpId = "explicit.example.com",
            origins = listOf("https://explicit.example.com"),
        )
        val objectConverter = mockk<ObjectConverter>()
        val cborConverter = mockk<CborConverter>()
        every { WebAuthnManagerFactory.objectConverter } returns objectConverter
        every { objectConverter.cborConverter } returns cborConverter
        every {
            cborConverter.readValue(any<ByteArray>(), COSEKey::class.java)
        } returns null

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "invalid_credential_key")
    }

    @Test
    fun `sign count regression rejects potentially cloned credential`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            clientIp = "192.0.2.109",
        )
        validCredential(principal, signCount = 5)
        stubWebAuthnVerification(signCount = 5)

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.Unauthorized, "credential_compromised")
    }

    @Test
    fun `successful authentication updates counter creates admin session and returns tokens`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            clientIp = "192.0.2.110",
        )
        val credential = validCredential(principal, signCount = 5)
        stubWebAuthnVerification(signCount = 6)
        val loginResponse = LoginResponse(
            principalId = principal.id,
            refreshToken = "refresh",
            token = Token(expiresAt = 3600, issuedAt = 1, token = "signed"),
        )
        coEvery {
            securityService.updatePasskeySignCount(credential.id, 6)
        } returns Unit
        coEvery {
            securityService.loginWithPasskey(principal.id, "credential", true)
        } returns loginResponse
        coEvery {
            securityService.getPrincipalGroups(principal.id)
        } returns listOf(
            Group(
                id = UUID.random(),
                name = "administrators",
                description = "Administrators",
                type = GroupType.SYSTEM,
            ),
        )

        route().execute(testCall.call)

        coVerify { securityService.updatePasskeySignCount(credential.id, 6) }
        verify { testCall.sessions.clear() }
        verify { testCall.sessions.set(Session(loginResponse, true)) }
        verify {
            testCall.response.respondText(
                match {
                    it.contains("\"token\":\"signed\"") &&
                        it.contains("\"refreshToken\":\"refresh\"")
                },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `zero counters authenticate without persistence update`() = runTest {
        val principal = Principal(id = UUID.random())
        val testCall = requestCall(
            userHandle = principal.id.toString(),
            clientIp = "192.0.2.111",
        )
        validCredential(principal, signCount = 0)
        stubWebAuthnVerification(signCount = 0, includeAuthenticatorData = false)
        val loginResponse = LoginResponse(
            principalId = principal.id,
            refreshToken = null,
            token = Token(expiresAt = 3600, issuedAt = 1, token = "signed"),
        )
        coEvery {
            securityService.loginWithPasskey(any(), any(), any())
        } returns loginResponse
        coEvery { securityService.getPrincipalGroups(principal.id) } returns emptyList()

        route().execute(testCall.call)

        coVerify(exactly = 0) { securityService.updatePasskeySignCount(any(), any()) }
        verify { testCall.sessions.set(Session(loginResponse, false)) }
    }

    @Test
    fun `eleventh completion request from an address is rate limited`() = runTest {
        val route = route()
        coEvery { stateManager.retrieveAndRemoveChallenge(any()) } returns null

        repeat(10) {
            route.execute(requestCall(clientIp = "198.51.100.201").call)
        }
        val limited = requestCall(clientIp = "198.51.100.201")
        route.execute(limited.call)

        verifyError(limited, HttpStatusCode.TooManyRequests, "rate_limited")
    }

    private fun validState() {
        coEvery {
            stateManager.retrieveAndRemoveChallenge("state")
        } returns WebAuthnChallengeState("AQ")
    }

    private fun validCredential(
        principal: Principal,
        signCount: Long = 0,
    ) = passkeyCredential(
        principal.id,
        identifier = "credential",
        publicKeyCose = "Aw",
        signCount = signCount,
    ).also { credential ->
        validState()
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery { securityService.getCredentials(principal, any()) } returns listOf(credential)
    }

    private fun stubWebAuthnVerification(
        signCount: Long = 0,
        error: BadSignatureException? = null,
        includeAuthenticatorData: Boolean = true,
    ) {
        val objectConverter = mockk<ObjectConverter>()
        val cborConverter = mockk<CborConverter>()
        val coseKey = mockk<COSEKey>()
        val manager = mockk<WebAuthnManager>()
        every { WebAuthnManagerFactory.objectConverter } returns objectConverter
        every { objectConverter.cborConverter } returns cborConverter
        every {
            cborConverter.readValue(any<ByteArray>(), COSEKey::class.java)
        } returns coseKey
        every { WebAuthnManagerFactory.webAuthnManager } returns manager
        if (error != null) {
            every {
                manager.verify(any<AuthenticationRequest>(), any<AuthenticationParameters>())
            } throws error
        } else {
            val authenticationData = mockk<AuthenticationData>()
            if (includeAuthenticatorData) {
                val authenticatorData =
                    mockk<AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>>()
                every { authenticatorData.signCount } returns signCount
                every { authenticationData.authenticatorData } returns authenticatorData
            } else {
                every { authenticationData.authenticatorData } returns null
            }
            every {
                manager.verify(any<AuthenticationRequest>(), any<AuthenticationParameters>())
            } returns authenticationData
        }
    }

    private fun route() = PasskeyAuthenticateComplete(
        securityService,
        configuration,
        stateManager,
    )

    private fun requestCall(
        credentialId: String = "credential",
        authenticatorData: String = "BA",
        clientDataJSON: String = "BQ",
        signature: String = "Bg",
        userHandle: String? = UUID.random().toString(),
        clientIp: String?,
    ) = SecurityRouteTestCall(
        body = buildString {
            append("""{"stateKey":"state","credentialId":"$credentialId","authenticatorData":"$authenticatorData","clientDataJSON":"$clientDataJSON","signature":"$signature"""")
            userHandle?.let { append(""","userHandle":"$it"""") }
            append("}")
        },
        clientIp = clientIp,
    )

    private fun verifyError(
        testCall: SecurityRouteTestCall,
        status: HttpStatusCode,
        error: String,
    ) {
        verify {
            testCall.response.respondText(
                match { it.contains(error) },
                ContentType.Application.Json,
                status,
            )
        }
    }
}
