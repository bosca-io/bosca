package bosca.security.graphql

import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.di.ProviderRegistry
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.CredentialPasswordAttributes
import bosca.security.model.CredentialType
import bosca.security.model.HashedEncodedPassword
import bosca.security.model.LoginResponse
import bosca.security.model.Principal
import bosca.security.model.PrincipalCredential
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.model.Token
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.JsonConverter.asJsonElement
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun pwHash(value: String): HashedEncodedPassword =
    object : HashedEncodedPassword { override val hash = value }

@OptIn(InternalDI::class)
class PrincipalMutationControllerTest {

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val profileService = mockk<ProfileService>()

    private val controller = PrincipalMutationController(
        securityService,
        groupEvaluator,
        profileService
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json.Default }

        mockkStatic("bosca.db.ConnectionManagerKt")
        
        // Mocking the static transaction function
        coEvery { 
            bosca.db.transaction<Boolean>(any()) 
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Boolean
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `identifier updates the identifier`() = runTest {
        val principalId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val principalObj = Principal(id = principalId)
        val attributes = CredentialPasswordAttributes("old-id", pwHash("hashed"))
        val credential = PrincipalCredential(
            principal = principalId,
            attributes = attributes
        )
        val loginResponse = LoginResponse(principalId, null, Token(0, 0, "token"))

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principalObj
        coEvery { securityService.getPrincipalById(principalId) } returns principalObj
        coEvery { securityService.getCredentials(principalObj) } returns listOf(credential)
        coEvery { securityService.loginWithCredential(any(), any()) } returns loginResponse
        coEvery { securityService.updateIdentifier(principalId, "new-id") } returns Unit

        val result = controller.identifier(authentication, "new-id", "password")

        assertTrue(result)
        coVerify { 
            securityService.updateIdentifier(principalId, "new-id")
        }
    }

    @Test
    fun `identifier updates the identifier with scrypt credential`() = runTest {
        val principalId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val principalObj = Principal(id = principalId)
        val attributes = ScryptCredentialAttributes(
            salt = "salt",
            identifier = "old-id",
            passwordHash = "hashed-old"
        )
        val credential = PrincipalCredential(
            principal = principalId,
            type = CredentialType.PASSWORD_SCRYPT,
            attributesJson = attributes.asJsonElement()
        )
        val loginResponse = LoginResponse(principalId, null, Token(0, 0, "token"))

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principalObj
        coEvery { securityService.getPrincipalById(principalId) } returns principalObj
        coEvery { securityService.getCredentials(principalObj) } returns listOf(credential)
        coEvery { securityService.loginWithCredential(any(), any()) } returns loginResponse
        coEvery { securityService.updateIdentifier(principalId, "new-id") } returns Unit

        val result = controller.identifier(authentication, "new-id", "password")

        assertTrue(result)
        coVerify {
            securityService.updateIdentifier(principalId, "new-id")
        }
    }

    @Test
    fun `identifier fails if login fails`() = runTest {
        val principalId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val principalObj = Principal(id = principalId)
        val attributes = CredentialPasswordAttributes("old-id", pwHash("hashed"))
        val credential = PrincipalCredential(
            principal = principalId,
            attributes = attributes
        )

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principalObj
        coEvery { securityService.getPrincipalById(principalId) } returns principalObj
        coEvery { securityService.getCredentials(principalObj) } returns listOf(credential)
        coEvery { securityService.loginWithCredential(any(), any()) } throws SecurityException("invalid password")

        assertFailsWith<SecurityException> {
            controller.identifier(authentication, "new-id", "password")
        }
        coVerify(exactly = 0) { securityService.updateIdentifier(any(), any()) }
    }

    @Test
    fun `identifier throws unauthorized if no principal`() = runTest {
        val authentication = mockk<AuthenticationContext>()

        every { authentication.principal() } returns null

        assertFailsWith<SecurityException> {
            controller.identifier(authentication, "new-id", "password")
        }
    }

    @Test
    fun `password updates the password when old password matches`() = runTest {
        val principalId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val principalObj = Principal(id = principalId)
        val attributes = CredentialPasswordAttributes("user-id", pwHash("hashed-old"))
        val credential = PrincipalCredential(
            principal = principalId,
            attributes = attributes
        )
        val loginResponse = LoginResponse(principalId, null, Token(0, 0, "token"))

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principalObj
        coEvery { securityService.getCredentials(principalObj) } returns listOf(credential)
        coEvery { securityService.loginWithCredential(any(), any()) } returns loginResponse
        coEvery { securityService.updatePassword(principalId, "new-pass", null) } returns Unit

        val result = controller.password(authentication, null, "new-pass", "old-pass")

        assertTrue(result)
        coVerify { 
            securityService.updatePassword(principalId, "new-pass", null)
        }
    }

    @Test
    fun `revoke login targets only the authenticated principal`() = runTest {
        val principalId = UUID.random()
        val authentication = authentication(principalId)
        coEvery { securityService.revokePrincipalLogin(principalId, 7L) } returns
            bosca.security.model.PrincipalLogin(7L, principalId, "password")
        coEvery { securityService.revokePrincipalLogin(principalId, 8L) } returns null

        assertTrue(controller.revokeLogin(authentication, 7L))
        assertFalse(controller.revokeLogin(authentication, 8L))

        coVerify(exactly = 1) {
            securityService.revokePrincipalLogin(principalId, 7L)
            securityService.revokePrincipalLogin(principalId, 8L)
        }
    }

    @Test
    fun `password updates the password when old scrypt password matches`() = runTest {
        val principalId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val principalObj = Principal(id = principalId)
        val attributes = ScryptCredentialAttributes(
            salt = "salt",
            identifier = "user-id",
            passwordHash = "hashed-old"
        )
        val credential = PrincipalCredential(
            principal = principalId,
            type = CredentialType.PASSWORD_SCRYPT,
            attributesJson = attributes.asJsonElement()
        )
        val loginResponse = LoginResponse(principalId, null, Token(0, 0, "token"))

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.asPrincipal() } returns principalObj
        coEvery { securityService.getCredentials(principalObj) } returns listOf(credential)
        coEvery { securityService.loginWithCredential(any(), any()) } returns loginResponse
        coEvery { securityService.updatePassword(principalId, "new-pass", null) } returns Unit

        val result = controller.password(authentication, null, "new-pass", "old-pass")

        assertTrue(result)
        coVerify {
            securityService.updatePassword(principalId, "new-pass", null)
        }
    }

    @Test
    fun `setPrimaryProfile updates the primary profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val ownedProfile = mockk<Profile>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        every { ownedProfile.id } returns profileId
        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(ownedProfile)
        coEvery { securityService.setPrimaryProfile(principalId, profileId) } returns Unit

        val result = controller.setPrimaryProfile(authentication, profileId, null)

        assertTrue(result)
        coVerify {
            securityService.setPrimaryProfile(principalId, profileId)
        }
    }

    @Test
    fun `identifier rejects missing persisted principal`() = runTest {
        val principalId = UUID.random()
        val authentication = authentication(principalId)
        coEvery { securityService.getPrincipalById(principalId) } returns null

        assertFailsWith<SecurityException> {
            controller.identifier(authentication, "new-id", "password")
        }
    }

    @Test
    fun `identifier rejects an account without a password credential`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = authentication(principalId, principal)
        coEvery { securityService.getPrincipalById(principalId) } returns principal
        coEvery { securityService.getCredentials(principal) } returns listOf(
            PrincipalCredential(
                principal = principalId,
                attributes = bosca.security.model.OAuth2CredentialAttributes(
                    identifier = "subject",
                    localId = null,
                    tokens = null,
                    source = "google",
                )
            )
        )

        assertFailsWith<SecurityException> {
            controller.identifier(authentication, "new-id", "password")
        }
    }

    @Test
    fun `password rejects invalid length missing authentication and missing credential`() = runTest {
        val authenticated = authentication(UUID.random())

        assertFailsWith<IllegalArgumentException> {
            controller.password(authenticated, null, "short", "old")
        }
        assertFailsWith<IllegalArgumentException> {
            controller.password(authenticated, null, "x".repeat(129), "old")
        }

        val missingAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        assertFailsWith<SecurityException> {
            controller.password(missingAuthentication, null, "valid-password", "old")
        }

        val principal = Principal(id = UUID.random())
        val withoutCredential = authentication(principal.id, principal)
        coEvery { securityService.getCredentials(principal) } returns emptyList()
        assertFailsWith<SecurityException> {
            controller.password(withoutCredential, null, "valid-password", "old")
        }
    }

    @Test
    fun `set primary profile supports admins and service accounts`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val authentication = authentication(principalId)
        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        coEvery { securityService.setPrimaryProfile(principalId, profileId) } returns Unit

        assertTrue(controller.setPrimaryProfile(authentication, profileId, principalId))

        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        assertTrue(controller.setPrimaryProfile(authentication, profileId, null))
        coVerify(exactly = 2) { securityService.setPrimaryProfile(principalId, profileId) }
    }

    @Test
    fun `set primary profile rejects unauthorized target and unowned profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val authentication = authentication(principalId)
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        assertFailsWith<SecurityException> {
            controller.setPrimaryProfile(authentication, profileId, UUID.random())
        }

        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<SecurityException> {
            controller.setPrimaryProfile(authentication, profileId, null)
        }
    }

    @Test
    fun `set and clear primary profile reject missing authentication`() = runTest {
        val authentication = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        assertFailsWith<SecurityException> {
            controller.setPrimaryProfile(authentication, UUID.random(), null)
        }
        assertFailsWith<SecurityException> {
            controller.clearPrimaryProfile(authentication, null)
        }
    }

    @Test
    fun `clear primary profile supports self and admin targets`() = runTest {
        val principalId = UUID.random()
        val targetId = UUID.random()
        val authentication = authentication(principalId)
        every { groupEvaluator.hasAdminGroup(authentication) } returns false
        coEvery { securityService.clearPrimaryProfile(principalId) } returns Unit

        assertTrue(controller.clearPrimaryProfile(authentication, null))

        every { groupEvaluator.hasAdminGroup(authentication) } returns true
        coEvery { securityService.clearPrimaryProfile(targetId) } returns Unit
        assertTrue(controller.clearPrimaryProfile(authentication, targetId))
    }

    @Test
    fun `clear primary profile rejects another target for non-admin`() = runTest {
        val authentication = authentication(UUID.random())
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        assertFailsWith<SecurityException> {
            controller.clearPrimaryProfile(authentication, UUID.random())
        }
    }

    private fun authentication(
        principalId: UUID,
        principal: Principal = Principal(id = principalId),
    ): AuthenticationContext {
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal> {
            every { id } returns principalId
            every { asPrincipal() } returns principal
        }
        return mockk {
            every { principal() } returns authenticatedPrincipal
        }
    }
}
