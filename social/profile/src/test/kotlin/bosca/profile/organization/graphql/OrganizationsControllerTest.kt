package bosca.profile.organization.graphql

import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.service.OrganizationService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
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
import kotlin.test.assertNotNull
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import bosca.profile.model.ProfileVisibility

class OrganizationsControllerTest {

    private lateinit var organizationService: OrganizationService
    private lateinit var securityService: SecurityService
    private lateinit var groupEvaluator: GroupEvaluator
    private lateinit var organizationPermissionEvaluator: OrganizationPermissionEvaluator
    private lateinit var controller: OrganizationsController
    private lateinit var authContext: AuthenticationContext

    @BeforeTest
    fun setup() {
        organizationService = mockk(relaxed = true)
        securityService = mockk(relaxed = true)
        groupEvaluator = GroupEvaluator(securityService)
        organizationPermissionEvaluator = OrganizationPermissionEvaluator(
            organizationService,
            securityService,
            groupEvaluator
        )
        controller = OrganizationsController(organizationService, organizationPermissionEvaluator, groupEvaluator)
        authContext = createAuthContext(emptyList())
    }

    @Test
    fun `all returns allowed organizations`() = runTest {
        val org1 = createOrganization("Org 1", ProfileVisibility.PUBLIC)
        val org2 = createOrganization("Org 2", ProfileVisibility.USER)

        coEvery { organizationService.getAll(0, 10) } returns listOf(org1, org2)
        
        // Setup permissions
        coEvery { organizationService.getPermissions(org1) } returns emptyList()
        coEvery { organizationService.getPermissions(org2) } returns emptyList()
        
        // Use a regular user context (no special groups)
        val userContext = createAuthContext(listOf(
            Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        ))
        
        val result = controller.all(userContext, 0, 10)

        assertEquals(2, result.size)
        assertEquals(org1.id, result[0].id)
    }

    @Test
    fun `organization returns organization`() = runTest {
        val org = createOrganization("Org 1", ProfileVisibility.PUBLIC)
        coEvery { organizationService.getOrganization(org.id) } returns org
        // Use a regular user context (no special groups)
        val userContext = createAuthContext(emptyList())
        val result = controller.organization(userContext, org.id)

        assertEquals(org, result)
    }
    
    // ... other tests
    
    @Test
    fun `findByToken returns organization`() = runTest {
        val org = createOrganization("Org 1", ProfileVisibility.PUBLIC)
        val token = OrganizationSignupToken(
            token = "valid-token",
            organizationId = org.id,
            groupId = UUID.random()
        )

        coEvery { organizationService.getSignupToken("valid-token") } returns token
        coEvery { organizationService.getOrganization(org.id) } returns org

        val result = controller.findByToken("valid-token")

        assertNotNull(result)
        assertEquals(org.id, result.id)
    }

    @Test
    fun `findByEmail returns organizations`() = runTest {
        val org = createOrganization("Org 1", ProfileVisibility.PUBLIC)
        val email = OrganizationSignupEmail(
            email = "test@example.com",
            organizationId = org.id,
            groupId = UUID.random(),
            created = OffsetDateTime.now(),
            expires = OffsetDateTime.now().plusDays(1)
        )

        coEvery { organizationService.getSignupEmail("test@example.com") } returns listOf(email)
        coEvery { organizationService.getOrganization(org.id) } returns org

        val result = controller.findByEmail(authContext, "test@example.com")

        assertEquals(1, result.size)
        assertEquals(org.id, result[0].id)
    }

    private fun createOrganization(name: String, visibility: ProfileVisibility): Organization {
        return Organization(
            id = UUID.random(),
            name = name,
            attributes = JsonObject(emptyMap()),
            systemAttributes = JsonObject(emptyMap()),
            visibility = visibility,
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
