package bosca.security.graphql

import bosca.di.annotation.InternalDI
import bosca.profile.profile.service.ProfileService
import bosca.profile.model.Profile
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.security.model.CredentialAttributes
import bosca.security.model.CredentialType
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.model.PrincipalCredential
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class PrincipalControllerTest {

    private val securityService = mockk<SecurityService>()
    private val profileService = mockk<ProfileService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = PrincipalController(
        securityService,
        profileService,
        profilePermissionEvaluator,
        groupEvaluator
    )

    @Test
    fun `attributes returns values to the owning principal`() {
        val principalId = UUID.random()
        val attributes = buildJsonObject { put("source", JsonPrimitive("test")) }
        val principal = Principal(id = principalId, attributes = attributes)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId

        assertEquals(attributes, controller.attributes(authentication, principal))
    }

    @Test
    fun `attributes are hidden from another non-administrator`() {
        val principal = Principal(
            id = UUID.random(),
            attributes = buildJsonObject { put("source", JsonPrimitive("test")) },
        )
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns UUID.random()
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        assertNull(controller.attributes(authentication, principal))
    }

    @Test
    fun `principal scalar fields and profiles are exposed`() = runTest {
        val created = OffsetDateTime.now()
        val modified = created.plusSeconds(1)
        val deleted = modified.plusSeconds(1)
        val profileId = UUID.random()
        val principal = Principal(
            id = UUID.random(),
            created = created,
            modified = modified,
            verified = true,
            anonymous = false,
            primaryProfileId = profileId,
            deletedAt = deleted,
        )
        val profiles = listOf(mockk<Profile>())
        val visibleProfiles = listOf(mockk<Profile>())
        coEvery { profileService.getByPrincipal(principal.id) } returns profiles
        coEvery {
            profilePermissionEvaluator.filterAllowed(null, profiles, PermissionAction.VIEW)
        } returns visibleProfiles

        assertEquals(principal.id, controller.id(principal))
        assertTrue(controller.verified(principal))
        assertEquals(false, controller.anonymous(principal))
        assertEquals(created, controller.created(principal))
        assertEquals(modified, controller.modified(principal))
        assertEquals(deleted, controller.deletedAt(principal))
        assertEquals(profileId, controller.primaryProfileId(principal))
        assertEquals(visibleProfiles, controller.profiles(null, principal))
    }

    @Test
    fun `attributes are visible to a service administrator`() {
        val attributes = buildJsonObject { put("source", JsonPrimitive("test")) }
        val principal = Principal(id = UUID.random(), attributes = attributes)
        every { groupEvaluator.hasSaGroup(null) } returns true

        assertEquals(attributes, controller.attributes(null, principal))
    }

    @Test
    fun `lastLogin returns null if not authenticated`() = runTest {
        every { groupEvaluator.hasSaGroup(null) } returns false
        val principal = Principal(id = UUID.random())
        val result = controller.lastLogin(null, principal)
        assertNull(result)
    }

    @Test
    fun `lastLogin returns date if authenticated as same principal`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val lastLogin = OffsetDateTime.now()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        coEvery { securityService.getPrincipalLastLogin(principalId) } returns lastLogin

        val result = controller.lastLogin(authentication, principal)
        assertEquals(lastLogin, result)
    }

    @Test
    fun `lastLogin returns null if authenticated as different principal and not admin`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns otherPrincipalId
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        val result = controller.lastLogin(authentication, principal)
        assertNull(result)
    }

    @Test
    fun `lastLogin returns date if authenticated as different principal and is admin`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val lastLogin = OffsetDateTime.now()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns otherPrincipalId
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { securityService.getPrincipalLastLogin(principalId) } returns lastLogin

        val result = controller.lastLogin(authentication, principal)
        assertEquals(lastLogin, result)
    }

    // --- credentials permission tests ---

    @Test
    fun `credentials returns empty list if not authenticated`() = runTest {
        val principal = Principal(id = UUID.random())
        every { groupEvaluator.hasSaGroup(null) } returns false
        val result = controller.credentials(null, principal)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `credentials returns values if authenticated as same principal`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val credential = mockk<PrincipalCredential>()
        val credentialAttributes = mockk<CredentialAttributes>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        every { credential.attributes } returns credentialAttributes
        every { credentialAttributes.identifier } returns "user@example.com"
        every { credential.type } returns CredentialType.PASSWORD
        every { credential.originator } returns "studio"
        every { credential.lastOriginator } returns "mobile"
        val oauthCredential = PrincipalCredential(
            principal = principalId,
            attributes = OAuth2CredentialAttributes(identifier = "google-subject", source = "google"),
        )
        coEvery { securityService.getCredentials(principal) } returns listOf(credential, oauthCredential)

        val result = controller.credentials(authentication, principal)
        assertEquals(2, result.size)
        assertEquals("user@example.com", result[0].identifier)
        assertEquals(CredentialType.PASSWORD, result[0].type)
        // The credential's original and last originator are surfaced.
        assertEquals("studio", result[0].originator)
        assertEquals("mobile", result[0].lastOriginator)
        assertEquals("google", result[1].provider)
    }

    @Test
    fun `credentials returns empty list if authenticated as different principal and not SA`() = runTest {
        val principal = Principal(id = UUID.random())
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns UUID.random()
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        val result = controller.credentials(authentication, principal)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `credentials returns values if authenticated as different principal and is SA`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val credential = mockk<PrincipalCredential>()
        val credentialAttributes = mockk<CredentialAttributes>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns UUID.random()
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        every { credential.attributes } returns credentialAttributes
        every { credentialAttributes.identifier } returns "user@example.com"
        every { credential.type } returns CredentialType.PASSWORD
        every { credential.originator } returns "studio"
        every { credential.lastOriginator } returns "studio"
        coEvery { securityService.getCredentials(principal) } returns listOf(credential)

        val result = controller.credentials(authentication, principal)
        assertEquals(1, result.size)
        assertEquals("user@example.com", result[0].identifier)
        assertEquals("studio", result[0].originator)
    }

    // --- groups permission tests ---

    @Test
    fun `groups returns empty list if not authenticated`() = runTest {
        val principal = Principal(id = UUID.random())
        every { groupEvaluator.hasSaGroup(null) } returns false
        val result = controller.groups(null, principal)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `groups returns values if authenticated as same principal`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val group = Group(name = "editors", description = "Editors", type = GroupType.SYSTEM)

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(group)

        val result = controller.groups(authentication, principal)
        assertEquals(1, result.size)
        assertEquals("editors", result[0].name)
    }

    @Test
    fun `groups returns empty list if authenticated as different principal and not SA`() = runTest {
        val principal = Principal(id = UUID.random())
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns UUID.random()
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        val result = controller.groups(authentication, principal)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `groups returns values if authenticated as different principal and is SA`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val group = Group(name = "editors", description = "Editors", type = GroupType.SYSTEM)

        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns UUID.random()
        every { groupEvaluator.hasSaGroup(authentication) } returns true
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(group)

        val result = controller.groups(authentication, principal)
        assertEquals(1, result.size)
        assertEquals("editors", result[0].name)
    }

    @Test
    fun `login history is visible to its owning principal`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        val history = listOf(PrincipalLogin(id = 4, principalId = principalId, method = "password"))
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId
        coEvery { securityService.getPrincipalLogins(principalId, 5, 20) } returns history

        assertEquals(history, controller.loginHistory(authentication, principal, 5, 20))
    }

    @Test
    fun `login history is hidden from another non-administrator`() = runTest {
        val principal = Principal(id = UUID.random())
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns UUID.random()
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        assertTrue(controller.loginHistory(authentication, principal, 0, 25).isEmpty())
    }

    @Test
    fun `login history validates pagination`() = runTest {
        val principalId = UUID.random()
        val principal = Principal(id = principalId)
        val authentication = mockk<AuthenticationContext>()
        val authenticatedPrincipal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns authenticatedPrincipal
        every { authenticatedPrincipal.id } returns principalId

        assertFailsWith<IllegalArgumentException> {
            controller.loginHistory(authentication, principal, -1, 25)
        }
        assertFailsWith<IllegalArgumentException> {
            controller.loginHistory(authentication, principal, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            controller.loginHistory(authentication, principal, 0, 101)
        }
    }

    @Test
    fun `principal login fields are exposed`() {
        val principalId = UUID.random()
        val created = OffsetDateTime.now()
        val revokedAt = created.plusMinutes(5)
        val login = PrincipalLogin(
            id = 7,
            principalId = principalId,
            method = "third_party",
            revokedAt = revokedAt,
            created = created,
        )
        val loginController = PrincipalLoginController()
        val currentAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList(), loginId = 7)
        }
        val otherAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList(), loginId = 8)
        }
        val emptyAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns null
        }
        val legacyAuthentication = mockk<AuthenticationContext> {
            every { principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        }

        assertEquals(7, loginController.id(login))
        assertEquals("third_party", loginController.method(login))
        assertEquals(revokedAt, loginController.revokedAt(login))
        assertTrue(loginController.current(currentAuthentication, login))
        assertFalse(loginController.current(otherAuthentication, login))
        assertFalse(loginController.current(emptyAuthentication, login))
        assertFalse(loginController.current(legacyAuthentication, login))
        assertFalse(loginController.current(null, login))
        assertEquals(created, loginController.created(login))
    }
}
