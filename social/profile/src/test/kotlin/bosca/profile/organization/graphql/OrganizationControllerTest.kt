package bosca.profile.organization.graphql

import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import bosca.profile.model.ProfileVisibility
import java.time.OffsetDateTime

class OrganizationControllerTest {

    private lateinit var organizationService: OrganizationService
    private lateinit var profileService: ProfileService
    private lateinit var securityService: SecurityService
    private lateinit var groupEvaluator: GroupEvaluator
    private lateinit var organizationPermissionEvaluator: OrganizationPermissionEvaluator
    private lateinit var controller: OrganizationController

    @BeforeTest
    fun setup() {
        organizationService = mockk(relaxed = true)
        profileService = mockk(relaxed = true)
        securityService = mockk(relaxed = true)
        groupEvaluator = GroupEvaluator(securityService)
        organizationPermissionEvaluator = OrganizationPermissionEvaluator(
            organizationService,
            securityService,
            groupEvaluator
        )
        controller = OrganizationController(
            profileService,
            organizationService,
            organizationPermissionEvaluator,
            securityService,
            groupEvaluator,
        )
    }

    @Test
    fun `domains returns empty list for non-admin`() = runTest {
        val org = createOrganization("Org 1")
        val userContext = createAuthContext(emptyList())

        val result = controller.domains(userContext, org)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `domains returns list for admin`() = runTest {
        val org = createOrganization("Org 1")
        val domain = OrganizationDomain(org.id, "example.com", true, null)
        
        coEvery { organizationService.getDomains(org.id) } returns listOf(domain)

        val adminContext = createAuthContext(listOf(
            Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        ))

        val result = controller.domains(adminContext, org)

        assertEquals(1, result.size)
        assertEquals("example.com", result[0].domain)
    }

    @Test
    fun `signupEmails returns empty list for non-admin`() = runTest {
        val org = createOrganization("Org 1")
        val userContext = createAuthContext(emptyList())

        val result = controller.signupEmails(userContext, org)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `signupEmails returns list for admin`() = runTest {
        val org = createOrganization("Org 1")
        val email = OrganizationSignupEmail("test@example.com", org.id, UUID.random(), OffsetDateTime.now(), OffsetDateTime.now())
        
        coEvery { organizationService.getSignupEmails(org.id) } returns listOf(email)

        val adminContext = createAuthContext(listOf(
            Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        ))

        val result = controller.signupEmails(adminContext, org)

        assertEquals(1, result.size)
        assertEquals("test@example.com", result[0].email)
    }

    @Test
    fun `signupTokens returns empty list for non-admin`() = runTest {
        val org = createOrganization("Org 1")
        val userContext = createAuthContext(emptyList())

        val result = controller.signupTokens(userContext, org)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `signupTokens returns list for admin`() = runTest {
        val org = createOrganization("Org 1")
        val token = OrganizationSignupToken("token", org.id, UUID.random())
        
        coEvery { organizationService.getSignupTokens(org.id) } returns listOf(token)

        val adminContext = createAuthContext(listOf(
            Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        ))

        val result = controller.signupTokens(adminContext, org)

        assertEquals(1, result.size)
        assertEquals("token", result[0].token)
    }

    private fun createOrganization(name: String): Organization {
        return Organization(
            id = UUID.random(),
            name = name,
            attributes = JsonObject(emptyMap()),
            systemAttributes = JsonObject(emptyMap()),
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
    }

    private fun createAuthContext(groups: List<Group>): AuthenticationContext {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, groups)
        val callAuthContext = mockk<bosca.server.auth.CallAuthenticationContext>(relaxed = true)
        every { callAuthContext.principal(any()) } returns authenticatedPrincipal
        val providers = AuthenticationProviders(arrayOf("default"))
        return AuthenticationContext(callAuthContext, providers)
    }
}
