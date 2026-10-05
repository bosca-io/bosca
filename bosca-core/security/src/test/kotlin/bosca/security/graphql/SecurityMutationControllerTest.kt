package bosca.security.graphql

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.CredentialPasswordAttributes
import bosca.security.model.HashedEncodedPassword
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.model.CredentialType
import bosca.serialization.JsonConverter.asJsonElement
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.ThirdPartyType
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class SecurityMutationControllerTest {

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = SecurityMutationController(securityService, groupEvaluator)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json.Default }
    }

    @Test
    fun `exposes all nested mutation namespaces`() {
        assertSame(ApiTokensMutation, controller.apiTokens())
        assertSame(PasskeysMutation, controller.passkeys())
        assertSame(LoginMutation, controller.login())
        assertSame(SignupMutation, controller.signup())
        assertSame(LinkMutation, controller.link())
        assertSame(AdminMutation, controller.admin())
        assertSame(PrincipalMutation, controller.principal())
        assertSame(GroupsMutation, controller.groups())
    }

    @Test
    fun `admin group mutations verify authorization and delegate`() = runTest {
        val principalId = UUID.random()
        val groupId = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        coEvery { securityService.addPrincipalGroup(principalId, groupId) } returns Unit
        coEvery { securityService.removePrincipalGroup(principalId, groupId) } returns Unit

        assertTrue(controller.addPrincipalGroup(authentication, principalId, groupId))
        assertTrue(controller.removePrincipalGroup(authentication, principalId, groupId))

        verify(exactly = 2) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { securityService.addPrincipalGroup(principalId, groupId) }
        coVerify { securityService.removePrincipalGroup(principalId, groupId) }
    }

    @Test
    fun `password reset validates length and handles invalid tokens`() = runTest {
        coEvery { securityService.resetPassword("valid", "long-enough-password") } returns Unit
        coEvery { securityService.resetPassword("invalid", any()) } throws
            IllegalArgumentException("expired")
        coEvery { securityService.resetPassword("missing", any()) } throws
            NoSuchElementException("missing")

        assertTrue(controller.password("long-enough-password", "valid"))
        assertFalse(controller.password("long-enough-password", "invalid"))
        assertFalse(controller.password("long-enough-password", "missing"))
        assertFailsWith<IllegalArgumentException> {
            controller.password("short", "valid")
        }
        assertFailsWith<IllegalArgumentException> {
            controller.password("x".repeat(129), "valid")
        }
    }

    @Test
    fun `admin can set a sufficiently long password`() = runTest {
        val principalId = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.updatePassword(principalId, "new-password") } returns Unit

        assertTrue(controller.setPassword(authentication, principalId, "new-password"))
        coVerify { securityService.updatePassword(principalId, "new-password") }
    }

    @Test
    fun `set password rejects short values before updating`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit

        assertFailsWith<IllegalArgumentException> {
            controller.setPassword(authentication, UUID.random(), "short")
        }
        coVerify(exactly = 0) { securityService.updatePassword(any(), any(), any()) }
    }

    @Test
    fun `send password reset resolves password identifier and request origin`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val credential = PrincipalCredential(
            principal = principalId,
            attributes = CredentialPasswordAttributes(
                identifier = "person@example.com",
                password = object : HashedEncodedPassword {
                    override val hash: String = "hash"
                },
            ),
        )
        val call = mockk<ServerCall> {
            every { request.appOrigin } returns "https://studio.example"
        }
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getCredentials(principal) } returns listOf(credential)
        coEvery {
            securityService.forgotPassword("person@example.com", "https://studio.example")
        } returns Unit

        assertTrue(controller.sendPasswordReset(authentication, principalId, call))
        coVerify {
            securityService.forgotPassword("person@example.com", "https://studio.example")
        }
    }

    @Test
    fun `send password reset rejects missing principal or password credential`() = runTest {
        val missingId = UUID.random()
        val credentiallessId = UUID.random()
        val principal = Principal(id = credentiallessId)
        val call = mockk<ServerCall>(relaxed = true)
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getPrincipalById(missingId) } returns null
        coEvery { securityService.getPrincipalById(credentiallessId) } returns principal
        coEvery { securityService.getCredentials(principal) } returns emptyList()

        assertFailsWith<NoSuchElementException> {
            controller.sendPasswordReset(authentication, missingId, call)
        }
        assertFailsWith<NoSuchElementException> {
            controller.sendPasswordReset(authentication, credentiallessId, call)
        }
    }

    @Test
    fun `send password reset accepts a scrypt password credential`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val attributes = ScryptCredentialAttributes(
            salt = "salt",
            identifier = "legacy@example.com",
            passwordHash = "hash",
        )
        val credential = PrincipalCredential(
            principal = principalId,
            type = CredentialType.PASSWORD_SCRYPT,
            attributesJson = attributes.asJsonElement(),
        )
        val call = mockk<ServerCall> {
            every { request.appOrigin } returns "https://studio.example"
        }
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getCredentials(principal) } returns listOf(credential)
        coEvery {
            securityService.forgotPassword("legacy@example.com", "https://studio.example")
        } returns Unit

        assertTrue(controller.sendPasswordReset(authentication, principalId, call))
    }

    @Test
    fun `credential and verification administration delegates after authorization`() = runTest {
        val principalId = UUID.random()
        val call = mockk<ServerCall> {
            every { request.appOrigin } returns "https://studio.example"
        }
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { securityService.deleteCredential(any(), any(), any()) } returns Unit
        coEvery { securityService.sendVerificationEmail(any(), any()) } returns Unit
        coEvery { securityService.deleteExpiredRefreshToken() } returns Unit

        assertTrue(
            controller.deleteCredential(
                authentication,
                principalId,
                bosca.security.model.CredentialType.API_TOKEN,
                "token-id",
            ),
        )
        assertTrue(controller.resendVerification(authentication, principalId, call))
        assertTrue(controller.expireRefreshTokens(authentication))

        coVerify {
            securityService.deleteCredential(
                principalId,
                bosca.security.model.CredentialType.API_TOKEN,
                "token-id",
            )
        }
        coVerify {
            securityService.sendVerificationEmail(principalId, "https://studio.example")
        }
        coVerify { securityService.deleteExpiredRefreshToken() }
    }

    @Test
    fun `API tokens cannot attach a third party login credential`() = runTest {
        for (scopes in listOf(listOf("content:view"), listOf("security:manage"), null)) {
            every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
                Principal(id = UUID.random(), anonymous = false), emptyList(), scopes, null, 1L,
            )
            assertFailsWith<SecurityException> {
                controller.connectThirdParty(authentication, ThirdPartyType.GOOGLE, "provider-token")
            }
        }
        coVerify(exactly = 0) { securityService.connectThirdParty(any(), any<ThirdPartyType>(), any()) }
    }

    @Test
    fun `authenticated principal can connect a third party account`() = runTest {
        val principal = mockk<AuthenticatedPrincipal> {
            every { id } returns UUID.random()
        }
        every { authentication.principal() } returns principal
        coEvery {
            securityService.connectThirdParty(principal.id, ThirdPartyType.GOOGLE, "provider-token")
        } returns mockk<PrincipalCredential>()

        assertTrue(
            controller.connectThirdParty(
                authentication,
                ThirdPartyType.GOOGLE,
                "provider-token",
            ),
        )
        coVerify {
            securityService.connectThirdParty(principal.id, ThirdPartyType.GOOGLE, "provider-token")
        }
    }

    @Test
    fun `API tokens cannot delete credentials`() = runTest {
        val principalId = UUID.random()
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        for (scopes in listOf(listOf("content:view"), listOf("security:manage"), null)) {
            every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
                Principal(id = principalId, anonymous = false), emptyList(), scopes, null, 1L,
            )
            for (type in listOf(CredentialType.OAUTH2, CredentialType.PASSKEY)) {
                assertFailsWith<SecurityException> {
                    controller.deleteCredential(authentication, principalId, type, "identifier")
                }
            }
        }
        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
    }

    @Test
    fun `principal with a password can disconnect their own oauth credential`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        every { authentication.principal() } returns AuthenticatedPrincipal(principal, emptyList())
        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getCredentials(principal) } returns listOf(
            PrincipalCredential(
                principal = principalId,
                attributes = CredentialPasswordAttributes(
                    "person@example.com",
                    object : HashedEncodedPassword { override val hash = "hash" },
                ),
            ),
        )
        coEvery {
            securityService.deleteCredential(principalId, CredentialType.OAUTH2, "google-subject")
        } returns Unit

        assertTrue(
            controller.deleteCredential(
                authentication,
                principalId,
                CredentialType.OAUTH2,
                "google-subject",
            ),
        )

        coVerify {
            securityService.deleteCredential(principalId, CredentialType.OAUTH2, "google-subject")
        }
    }

    @Test
    fun `principal without a password cannot disconnect their oauth credential`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        every { authentication.principal() } returns AuthenticatedPrincipal(principal, emptyList())
        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getCredentials(principal) } returns listOf(
            PrincipalCredential(
                principal = principalId,
                attributes = OAuth2CredentialAttributes(
                    identifier = "google-subject",
                    source = "google",
                ),
            ),
        )

        assertFailsWith<SecurityException> {
            controller.deleteCredential(
                authentication,
                principalId,
                CredentialType.OAUTH2,
                "google-subject",
            )
        }
        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
    }

    @Test
    fun `principal cannot disconnect oauth when their principal record is missing`() = runTest {
        val principalId = UUID.random()
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        coEvery { securityService.getPrincipalById(principalId) } returns null

        assertFailsWith<SecurityException> {
            controller.deleteCredential(authentication, principalId, CredentialType.OAUTH2, "google-subject")
        }

        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
    }

    @Test
    fun `non-admin cannot disconnect another principals credential or a non-oauth credential`() = runTest {
        val principalId = UUID.random()
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        assertFailsWith<SecurityException> {
            controller.deleteCredential(authentication, UUID.random(), CredentialType.OAUTH2, "subject")
        }
        assertFailsWith<SecurityException> {
            controller.deleteCredential(authentication, principalId, CredentialType.API_TOKEN, "token")
        }

        coVerify(exactly = 0) { securityService.deleteCredential(any(), any(), any()) }
    }

    @Test
    fun `anonymous principal cannot connect a third party account`() = runTest {
        every { authentication.principal() } returns null

        assertFailsWith<SecurityException> {
            controller.connectThirdParty(authentication, ThirdPartyType.GOOGLE, "provider-token")
        }
    }
}
