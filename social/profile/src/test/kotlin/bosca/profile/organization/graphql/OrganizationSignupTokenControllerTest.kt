package bosca.profile.organization.graphql

import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationSignupTokenControllerTest {

    private val securityService = mockk<SecurityService>()
    private val controller = OrganizationSignupTokenController(securityService)

    @Test
    fun `type returns ADMINISTRATORS when group name ends with admins`() = runTest {
        val groupId = UUID.random()
        val group = Group(id = groupId, name = "org123.admins", description = "Org Admins", type = GroupType.SYSTEM)
        val token = OrganizationSignupToken(token = "abc", organizationId = UUID.random(), groupId = groupId)

        coEvery { securityService.getGroupById(groupId) } returns group

        val result = controller.type(token)

        assertEquals(OrganizationSignupGroupType.ADMINISTRATORS, result)
    }

    @Test
    fun `type returns USERS when group name does not end with admins`() = runTest {
        val groupId = UUID.random()
        val group = Group(id = groupId, name = "org123.users", description = "Org Users", type = GroupType.SYSTEM)
        val token = OrganizationSignupToken(token = "abc", organizationId = UUID.random(), groupId = groupId)

        coEvery { securityService.getGroupById(groupId) } returns group

        val result = controller.type(token)

        assertEquals(OrganizationSignupGroupType.USERS, result)
    }

    @Test
    fun `token returns the token string`() {
        val token = OrganizationSignupToken(token = "my-signup-token", organizationId = UUID.random(), groupId = UUID.random())

        assertEquals("my-signup-token", controller.token(token))
    }

    @Test
    fun `group returns group from security service`() = runTest {
        val groupId = UUID.random()
        val group = Group(id = groupId, name = "org123.users", description = "Org Users", type = GroupType.SYSTEM)
        val token = OrganizationSignupToken(token = "abc", organizationId = UUID.random(), groupId = groupId)

        coEvery { securityService.getGroupById(groupId) } returns group

        val result = controller.group(token)

        assertEquals(group, result)
    }
}
