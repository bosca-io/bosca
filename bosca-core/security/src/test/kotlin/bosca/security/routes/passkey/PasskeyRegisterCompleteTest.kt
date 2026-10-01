package bosca.security.routes.passkey

import bosca.security.model.CredentialType
import bosca.security.model.Principal
import bosca.security.routes.security.SecurityRouteTestCall
import bosca.security.routes.security.SecurityRouteTestEnvironment
import bosca.security.routes.security.authRateLimitCacheManager
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.WebAuthnConfiguration
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.CborConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import com.webauthn4j.verifier.exception.BadSignatureException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class PasskeyRegisterCompleteTest {

    private val securityService = mockk<SecurityService>()
    private val stateManager = mockk<WebAuthnStateManager>()
    private val configuration = mockk<SecurityConfiguration> {
        every { domain } returns "example.com"
        every { adminDomain } returns "admin.example.com"
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
    fun `unauthenticated registration is rejected`() = runTest {
        val testCall = requestCall()

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.Unauthorized, "not_authenticated")
    }

    @Test
    fun `API token cannot complete registration or consume a challenge`() = runTest {
        val testCall = requestCall()
        testCall.authenticationContext.principal("test", ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), emptyList(), null, null, 1L,
        ))
        route().execute(testCall.call)
        verifyError(testCall, HttpStatusCode.Forbidden, "api_token_not_allowed")
        coVerify(exactly = 0) { stateManager.retrieveAndRemoveChallenge(any()) }
        coVerify(exactly = 0) { securityService.linkCredentialToPrincipal(any(), any()) }
    }

    @Test
    fun `blank and oversized credential names are rejected`() = runTest {
        val route = route()
        val blank = requestCall(name = "  ").also { it.authenticate() }
        val oversized = requestCall(name = "x".repeat(256)).also { it.authenticate() }

        route.execute(blank.call)
        route.execute(oversized.call)

        verifyError(blank, HttpStatusCode.BadRequest, "invalid_name")
        verifyError(oversized, HttpStatusCode.BadRequest, "invalid_name")
    }

    @Test
    fun `expired challenge and principal mismatch are rejected`() = runTest {
        val route = route()
        val expired = requestCall().also { it.authenticate() }
        val mismatch = requestCall().also { it.authenticate() }
        coEvery {
            stateManager.retrieveAndRemoveChallenge("state")
        } returns null andThen WebAuthnChallengeState("AQ", UUID.random())

        route.execute(expired.call)
        route.execute(mismatch.call)

        verifyError(expired, HttpStatusCode.BadRequest, "challenge_expired")
        verifyError(mismatch, HttpStatusCode.Forbidden, "principal_mismatch")
    }

    @Test
    fun `invalid attestation encoding is rejected`() = runTest {
        val testCall = requestCall(attestationObject = "%")
        val principal = testCall.authenticate()
        validState(principal)

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "invalid_encoding")
    }

    @Test
    fun `WebAuthn registration verification failure is rejected`() = runTest {
        val testCall = requestCall()
        val principal = testCall.authenticate()
        validState(principal)
        stubRegistration(error = BadSignatureException("bad signature"))

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "verification_failed")
    }

    @Test
    fun `registration without attested credential data is rejected`() = runTest {
        val testCall = requestCall()
        val principal = testCall.authenticate()
        validState(principal)
        stubRegistration(missingCredentialData = true)

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.BadRequest, "missing_credential_data")
    }

    @Test
    fun `registration without an attestation object is rejected`() = runTest {
        val missingAttestation = requestCall()
        val firstPrincipal = missingAttestation.authenticate()
        validState(firstPrincipal)
        stubRegistration(missingAttestationObject = true)

        route().execute(missingAttestation.call)
        verifyError(missingAttestation, HttpStatusCode.BadRequest, "missing_credential_data")
    }

    @Test
    fun `duplicate registered credential is rejected`() = runTest {
        val testCall = requestCall()
        val principal = testCall.authenticate()
        validState(principal)
        val credentialId = stubRegistration()
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery {
            securityService.getCredentials(principal, CredentialType.PASSKEY)
        } returns listOf(passkeyCredential(principal.id, identifier = credentialId))

        route().execute(testCall.call)

        verifyError(testCall, HttpStatusCode.Conflict, "duplicate_credential")
        coVerify(exactly = 0) { securityService.addPasskeyCredential(any(), any()) }
    }

    @Test
    fun `successful registration trims name and stores credential metadata`() = runTest {
        val explicitConfiguration = mockk<SecurityConfiguration> {
            every { domain } returns "example.com"
            every { adminDomain } returns "admin.example.com"
            every { webauthn } returns WebAuthnConfiguration(
                rpId = "login.example.com",
                origins = listOf("https://studio.example"),
            )
        }
        val testCall = requestCall(name = "  Work key  ")
        val principal = testCall.authenticate()
        validState(principal)
        val credentialId = stubRegistration(
            signCount = 9,
            aaguidValue = java.util.UUID.randomUUID(),
        )
        coEvery { securityService.getPrincipalById(principal.id) } returns principal
        coEvery {
            securityService.getCredentials(principal, CredentialType.PASSKEY)
        } returns emptyList()
        coEvery {
            securityService.addPasskeyCredential(principal.id, any())
        } returns mockk()

        PasskeyRegisterComplete(
            securityService,
            explicitConfiguration,
            stateManager,
        ).execute(testCall.call)

        coVerify {
            securityService.addPasskeyCredential(
                principal.id,
                match {
                    it.identifier == credentialId &&
                        it.name == "Work key" &&
                        it.signCount == 9L &&
                        it.transports == listOf("internal")
                },
            )
        }
        verify {
            testCall.response.respondText(
                match {
                    it.contains("\"credentialId\":\"$credentialId\"") &&
                        it.contains("\"name\":\"Work key\"")
                },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    private fun validState(principal: Principal) {
        coEvery {
            stateManager.retrieveAndRemoveChallenge("state")
        } returns WebAuthnChallengeState("AQ", principal.id)
    }

    private fun stubRegistration(
        signCount: Long = 0,
        missingCredentialData: Boolean = false,
        missingAttestationObject: Boolean = false,
        aaguidValue: java.util.UUID? = null,
        error: BadSignatureException? = null,
    ): String {
        val objectConverter = mockk<ObjectConverter>()
        val cborConverter = mockk<CborConverter>()
        val manager = mockk<WebAuthnManager>()
        every { WebAuthnManagerFactory.objectConverter } returns objectConverter
        every { objectConverter.cborConverter } returns cborConverter
        every { WebAuthnManagerFactory.webAuthnManager } returns manager
        if (error != null) {
            every {
                manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>())
            } throws error
            return ""
        }

        val authenticatorData =
            mockk<AuthenticatorData<RegistrationExtensionAuthenticatorOutput>>()
        val attestationObject = mockk<AttestationObject>()
        val registrationData = mockk<RegistrationData>()
        every {
            registrationData.attestationObject
        } returns if (missingAttestationObject) null else attestationObject
        every { attestationObject.authenticatorData } returns authenticatorData
        every { authenticatorData.signCount } returns signCount
        if (missingCredentialData) {
            every { authenticatorData.attestedCredentialData } returns null
        } else {
            val credentialData = mockk<AttestedCredentialData>()
            val coseKey = mockk<COSEKey>()
            every { credentialData.credentialId } returns byteArrayOf(7, 8, 9)
            every { credentialData.coseKey } returns coseKey
            val aaguid = aaguidValue?.let { uuidValue ->
                val aaguid = mockk<AAGUID>()
                every { aaguid.value } returns uuidValue
                aaguid
            } ?: AAGUID.NULL
            every { credentialData.aaguid } returns aaguid
            every { authenticatorData.attestedCredentialData } returns credentialData
            every { cborConverter.writeValueAsBytes(coseKey) } returns byteArrayOf(1, 2, 3)
        }
        every {
            manager.verify(any<RegistrationRequest>(), any<RegistrationParameters>())
        } returns registrationData
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(7, 8, 9))
    }

    private fun route() = PasskeyRegisterComplete(
        securityService,
        configuration,
        stateManager,
    )

    private fun requestCall(
        name: String = "Laptop",
        attestationObject: String = "Ag",
        clientDataJSON: String = "Aw",
    ) = SecurityRouteTestCall(
        body = """{"stateKey":"state","name":"$name","credentialId":"unused","attestationObject":"$attestationObject","clientDataJSON":"$clientDataJSON","transports":["internal"]}""",
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
