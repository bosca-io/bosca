package bosca.community.model

import bosca.security.model.PermissionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class CommunityGroupPermissionTest {

    @Test
    fun `CommunityGroupPermission entityId equals communityGroupId`() {
        val communityGroupId = Uuid.random()
        val groupId = Uuid.random()
        val permission = CommunityGroupPermission(
            communityGroupId = communityGroupId, groupId = groupId, action = PermissionAction.MANAGE
        )
        assertEquals(communityGroupId, permission.entityId)
        assertEquals(communityGroupId, permission.communityGroupId)
    }

    @Test
    fun `CommunityGroupPermission stores groupId and action`() {
        val communityGroupId = Uuid.random()
        val groupId = Uuid.random()
        val permission = CommunityGroupPermission(
            communityGroupId = communityGroupId, groupId = groupId, action = PermissionAction.LIST
        )
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.LIST, permission.action)
    }
}
