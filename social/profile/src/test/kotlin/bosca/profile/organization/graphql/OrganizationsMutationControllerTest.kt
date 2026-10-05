package bosca.profile.organization.graphql

import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationInput
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupEmailInput
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.model.OrganizationSignupTokenInput
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.model.ProfileInput
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import bosca.profile.model.ProfileVisibility

class OrganizationsMutationControllerTest {

    private lateinit var organizationService: OrganizationService
    private lateinit var securityService: SecurityService
    private lateinit var groupEvaluator: GroupEvaluator
    private lateinit var organizationPermissionEvaluator: OrganizationPermissionEvaluator
    private lateinit var controller: OrganizationsMutationController

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
        controller = OrganizationsMutationController(organizationService, organizationPermissionEvaluator, groupEvaluator)
    }

    @Test
    fun `add delegates to service without auth check`() = runTest {
        val orgInput = OrganizationInput(
            name = "New Org",
            attributes = JsonObject(emptyMap()),
            systemAttributes = JsonObject(emptyMap()),
            visibility = ProfileVisibility.PUBLIC
        )
        val profileInput = ProfileInput(
            name = "New Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val org = createOrganization("New Org")

        coEvery { organizationService.add(orgInput, profileInput) } returns org

        val result = controller.add(orgInput, profileInput)

        assertEquals(org, result)
        coVerify { organizationService.add(orgInput, profileInput) }
    }

    @Test
    fun `edit checks permission and calls service`() = runTest {
        val orgId = UUID.random()
        val orgInput = OrganizationInput(
            id = orgId,
            name = "Updated Org",
            attributes = JsonObject(emptyMap()),
            systemAttributes = JsonObject(emptyMap()),
            visibility = ProfileVisibility.PUBLIC
        )
        val profileInput = ProfileInput(
            name = "Updated Profile",
            visibility = ProfileVisibility.PUBLIC
        )
        val currentOrg = createOrganization("Current Org").copy(id = orgId)
        val updatedOrg = createOrganization("Updated Org").copy(id = orgId)

        coEvery { organizationService.getOrganization(orgId) } returns currentOrg
        coEvery { organizationService.getPermissions(currentOrg) } returns emptyList()

        coEvery { organizationService.edit(orgInput, profileInput) } returns updatedOrg

        // Use Editor context
        val editorGroup = Group(UUID.random(), "editors", "", GroupType.SYSTEM)
        val authContext = createAuthContext(listOf(editorGroup))

        val result = controller.edit(authContext, orgInput, profileInput)

        assertEquals(updatedOrg, result)
        coVerify { organizationService.edit(orgInput, profileInput) }
    }

    @Test
    fun `delete checks permission and calls service`() = runTest {
        val orgId = UUID.random()
        val currentOrg = createOrganization("Current Org").copy(id = orgId)

        coEvery { organizationService.getOrganization(orgId) } returns currentOrg
        coEvery { organizationService.getPermissions(currentOrg) } returns emptyList()

        coEvery { organizationService.delete(orgId) } just Runs

        // Use SA context
        val saGroup = Group(UUID.random(), "sa", "", GroupType.SYSTEM)
        val authContext = createAuthContext(listOf(saGroup))

        val result = controller.delete(authContext, orgId)

        assertTrue(result)
        coVerify { organizationService.delete(orgId) }
    }

    @Test
    fun `addSignupToken checks permission and calls service`() = runTest {
        val orgId = UUID.random()
        val currentOrg = createOrganization("Current Org").copy(id = orgId)
        val tokenInput = OrganizationSignupTokenInput(OrganizationSignupGroupType.USERS)
        val token = OrganizationSignupToken("token", orgId, UUID.random())

        coEvery { organizationService.getOrganization(orgId) } returns currentOrg
        coEvery { organizationService.getPermissions(currentOrg) } returns emptyList()

        coEvery { organizationService.addSignupToken(orgId, tokenInput) } returns token

        // Use Admin context
        val adminGroup = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        val authContext = createAuthContext(listOf(adminGroup))

        val result = controller.addSignupToken(authContext, orgId, tokenInput)

        assertEquals(currentOrg, result)
        coVerify { organizationService.addSignupToken(orgId, tokenInput) }
    }

    @Test
    fun `deleteSignupToken checks permission and calls service`() = runTest {
        val orgId = UUID.random()
        val currentOrg = createOrganization("Current Org").copy(id = orgId)
        val token = "token-string"

        coEvery { organizationService.getOrganization(orgId) } returns currentOrg
        coEvery { organizationService.getPermissions(currentOrg) } returns emptyList()

        coEvery { organizationService.deleteSignupToken(orgId, token) } just Runs

        // Use Admin context
        val adminGroup = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        val authContext = createAuthContext(listOf(adminGroup))

        val result = controller.deleteSignupToken(authContext, orgId, token)

        assertEquals(currentOrg, result)
        coVerify { organizationService.deleteSignupToken(orgId, token) }
    }

    @Test
    fun `addSignupEmailToken checks permission and calls service`() = runTest {
        val orgId = UUID.random()
        val currentOrg = createOrganization("Current Org").copy(id = orgId)
        val emailInput = OrganizationSignupEmailInput("test@example.com", OrganizationSignupGroupType.USERS)
        val email = OrganizationSignupEmail("test@example.com", orgId, UUID.random(), OffsetDateTime.now(), OffsetDateTime.now())

        coEvery { organizationService.getOrganization(orgId) } returns currentOrg
        coEvery { organizationService.getPermissions(currentOrg) } returns emptyList()

        coEvery { organizationService.addSignupEmail(orgId, emailInput) } returns email

        // Use Admin context
        val adminGroup = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        val authContext = createAuthContext(listOf(adminGroup))

        val result = controller.addSignupEmailToken(authContext, orgId, emailInput)

        assertEquals(currentOrg, result)
        coVerify { organizationService.addSignupEmail(orgId, emailInput) }
    }

    @Test
    fun `deleteSignupEmailToken checks permission and calls service`() = runTest {
        val orgId = UUID.random()
        val currentOrg = createOrganization("Current Org").copy(id = orgId)
        val token = "test@example.com"

        coEvery { organizationService.getOrganization(orgId) } returns currentOrg
        coEvery { organizationService.getPermissions(currentOrg) } returns emptyList()

        coEvery { organizationService.deleteSignupEmail(orgId, token) } just Runs

        // Use Admin context
        val adminGroup = Group(UUID.random(), "administrators", "", GroupType.SYSTEM)
        val authContext = createAuthContext(listOf(adminGroup))

        val result = controller.deleteSignupEmailToken(authContext, orgId, token)

        assertEquals(currentOrg, result)
        coVerify { organizationService.deleteSignupEmail(orgId, token) }
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
