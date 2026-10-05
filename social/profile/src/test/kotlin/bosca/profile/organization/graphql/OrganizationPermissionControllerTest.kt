package bosca.profile.organization.graphql

import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.organization.model.OrganizationSignupGroupType
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationPermissionControllerTest {

    private val securityService = mockk<SecurityService>()
    private val controller = OrganizationPermissionController(securityService)

    @Test
    fun `group returns group from security service`() = runTest {
        val groupId = UUID.random()
        val group = Group(id = groupId, name = "test.admins", description = "Test Admins", type = GroupType.SYSTEM)
        val permission = OrganizationPermission(organizationId = UUID.random(), groupId = groupId, action = PermissionAction.MANAGE)

        coEvery { securityService.getGroupById(groupId) } returns group

        val result = controller.group(permission)

        assertEquals(group, result)
    }

    @Test
    fun `groupType returns ADMINISTRATORS when group name ends with admins`() = runTest {
        val groupId = UUID.random()
        val group = Group(id = groupId, name = "org123.admins", description = "Org Admins", type = GroupType.SYSTEM)
        val permission = OrganizationPermission(organizationId = UUID.random(), groupId = groupId, action = PermissionAction.MANAGE)

        coEvery { securityService.getGroupById(groupId) } returns group

        val result = controller.groupType(permission)

        assertEquals(OrganizationSignupGroupType.ADMINISTRATORS, result)
    }

    @Test
    fun `groupType returns USERS when group name does not end with admins`() = runTest {
        val groupId = UUID.random()
        val group = Group(id = groupId, name = "org123.users", description = "Org Users", type = GroupType.SYSTEM)
        val permission = OrganizationPermission(organizationId = UUID.random(), groupId = groupId, action = PermissionAction.MANAGE)

        coEvery { securityService.getGroupById(groupId) } returns group

        val result = controller.groupType(permission)

        assertEquals(OrganizationSignupGroupType.USERS, result)
    }

    @Test
    fun `action returns permission action`() {
        val permission = OrganizationPermission(
            organizationId = UUID.random(),
            groupId = UUID.random(),
            action = PermissionAction.VIEW
        )

        assertEquals(PermissionAction.VIEW, controller.action(permission))
    }

    @Test
    fun `action returns MANAGE permission`() {
        val permission = OrganizationPermission(
            organizationId = UUID.random(),
            groupId = UUID.random(),
            action = PermissionAction.MANAGE
        )

        assertEquals(PermissionAction.MANAGE, controller.action(permission))
    }
}
