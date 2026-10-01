package bosca.security.routes.passkey

import bosca.security.model.CredentialType
import bosca.security.model.Principal
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.routes.security.SecurityRouteTestCall
import bosca.security.routes.security.SecurityRouteTestEnvironment
import bosca.security.routes.security.authRateLimitCacheManager
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityService
import bosca.security.service.WebAuthnConfiguration
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class PasskeySimpleRoutesTest {

    private val securityService = mockk<SecurityService>()
    private val stateManager = mockk<WebAuthnStateManager>()
    private val configuration = mockk<SecurityConfiguration> {
        every { domain } returns "example.com"
        every { webauthn } returns WebAuthnConfiguration(
            rpName = "Bosca Test",
            rpId = null,
        )
    }
    private lateinit var environment: SecurityRouteTestEnvironment

    @BeforeTest
    fun setUp() {
        environment = SecurityRouteTestEnvironment(authRateLimitCacheManager())
    }

    @AfterTest
    fun tearDown() {
        environment.close()
    }

    @Test
    fun `authentication begin creates discoverable challenge with configured fallback RP`() = runTest {
        val testCall = SecurityRouteTestCall(clientIp = "192.0.2.44")
        coEvery {
            stateManager.createChallenge(null)
        } returns ("state-key" to WebAuthnChallengeState("challenge"))

        PasskeyAuthenticateBegin(configuration, stateManager).execute(testCall.call)

        coVerify { stateManager.createChallenge(null) }
        verify {
            testCall.response.respondText(
                match {
                    it.contains("\"stateKey\":\"state-key\"") &&
                        it.contains("\"rpId\":\"example.com\"")
                },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `authentication begin uses explicit RP id and unknown client address`() = runTest {
        val explicitConfiguration = mockk<SecurityConfiguration> {
            every { webauthn } returns WebAuthnConfiguration(rpId = "login.example.com")
        }
        val testCall = SecurityRouteTestCall(clientIp = null)
        coEvery {
            stateManager.createChallenge(null)
        } returns ("state-key" to WebAuthnChallengeState("challenge"))

        PasskeyAuthenticateBegin(explicitConfiguration, stateManager).execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("\"rpId\":\"login.example.com\"") },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `authentication begin eleventh request from an address is rate limited`() = runTest {
        val route = PasskeyAuthenticateBegin(configuration, stateManager)
        coEvery {
            stateManager.createChallenge(null)
        } returns ("state-key" to WebAuthnChallengeState("challenge"))

        repeat(10) {
            route.execute(SecurityRouteTestCall(clientIp = "198.51.100.72").call)
        }
        val limited = SecurityRouteTestCall(clientIp = "198.51.100.72")
        route.execute(limited.call)

        verify {
            limited.response.respondText(
                match { it.contains("rate_limited") },
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
        }
    }

    @Test
    fun `register begin rejects API tokens before creating a challenge`() = runTest {
        val testCall = SecurityRouteTestCall()
        testCall.authenticationContext.principal("test", ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), emptyList(), listOf("content:view"), null, 1L,
        ))
        PasskeyRegisterBegin(securityService, configuration, stateManager).execute(testCall.call)
        verify {
            testCall.response.respondText(match { it.contains("api_token_not_allowed") }, ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
        coVerify(exactly = 0) { stateManager.createChallenge(any()) }
        coVerify(exactly = 0) { securityService.getCredentials(any(), any()) }
    }

    @Test
    fun `interactive registration uses the explicitly configured relying party`() = runTest {
        val explicit = mockk<SecurityConfiguration> {
            every { webauthn } returns WebAuthnConfiguration(rpId = "login.example.com")
        }
        val testCall = SecurityRouteTestCall()
        val principal = testCall.authenticate()
        coEvery { securityService.getCredentials(principal, CredentialType.PASSKEY) } returns emptyList()
        coEvery { stateManager.createChallenge(principal.id) } returns (
            "state-key" to WebAuthnChallengeState("challenge", principal.id)
        )
        PasskeyRegisterBegin(securityService, explicit, stateManager).execute(testCall.call)
        verify {
            testCall.response.respondText(match { it.contains("login.example.com") }, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }

    @Test
    fun `register begin rejects unauthenticated caller`() = runTest {
        val testCall = SecurityRouteTestCall()

        PasskeyRegisterBegin(securityService, configuration, stateManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("not_authenticated") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `register begin returns existing credentials and user information`() = runTest {
        val testCall = SecurityRouteTestCall()
        val principal = testCall.authenticate()
        coEvery {
            securityService.getCredentials(principal, CredentialType.PASSKEY)
        } returns listOf(passkeyCredential(principal.id))
        coEvery {
            stateManager.createChallenge(principal.id)
        } returns ("state-key" to WebAuthnChallengeState("challenge", principal.id))

        PasskeyRegisterBegin(securityService, configuration, stateManager)
            .execute(testCall.call)

        verify {
            testCall.response.respondText(
                match {
                    it.contains("\"name\":\"Bosca Test\"") &&
                        it.contains("\"credential-id\"") &&
                        it.contains(principal.id.toString())
                },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `list rejects unauthenticated caller`() = runTest {
        val testCall = SecurityRouteTestCall()

        PasskeyList(securityService).execute(testCall.call)

        verify {
            testCall.response.respondText(
                match { it.contains("not_authenticated") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `list maps stored credential attributes`() = runTest {
        val testCall = SecurityRouteTestCall()
        val principal = testCall.authenticate()
        coEvery {
            securityService.getCredentials(principal, CredentialType.PASSKEY)
        } returns listOf(passkeyCredential(principal.id))

        PasskeyList(securityService).execute(testCall.call)

        verify {
            testCall.response.respondText(
                match {
                    it.contains("\"credentialId\":\"credential-id\"") &&
                        it.contains("\"lastUsedAt\":\"2026-07-24T00:00:00Z\"")
                },
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }

    @Test
    fun `delete rejects unauthenticated caller`() = runTest {
        val testCall = SecurityRouteTestCall(
            body = """{"credentialId":"credential-id"}""",
        )

        PasskeyDelete(securityService).execute(testCall.call)

        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
        verify {
            testCall.response.respondText(
                match { it.contains("not_authenticated") },
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `delete rejects API tokens`() = runTest {
        val testCall = SecurityRouteTestCall(
            body = """{"credentialId":"credential-id"}""",
        )
        testCall.authenticationContext.principal("test", ScopedAuthenticatedPrincipal(
            Principal(id = UUID.random(), anonymous = false), emptyList(), listOf("content:view"), null, 1L,
        ))

        PasskeyDelete(securityService).execute(testCall.call)

        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
        verify {
            testCall.response.respondText(
                match { it.contains("api_token_not_allowed") },
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
    }

    @Test
    fun `delete removes authenticated principal passkey`() = runTest {
        val testCall = SecurityRouteTestCall(
            body = """{"credentialId":"credential-id"}""",
        )
        val principal = testCall.authenticate()
        coEvery {
            securityService.deleteCredential(
                principal.id,
                CredentialType.PASSKEY,
                "credential-id",
            )
        } returns Unit

        PasskeyDelete(securityService).execute(testCall.call)

        coVerify {
            securityService.deleteCredential(
                principal.id,
                CredentialType.PASSKEY,
                "credential-id",
            )
        }
        verify {
            testCall.response.respondText(
                "",
                ContentType.Text.Plain,
                HttpStatusCode.NoContent,
            )
        }
    }
}
