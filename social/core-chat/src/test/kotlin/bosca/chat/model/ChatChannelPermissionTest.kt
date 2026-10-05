package bosca.chat.model

import bosca.security.model.PermissionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ChatChannelPermissionTest {

    @Test
    fun `ChatChannelPermission entityId equals channelId`() {
        val channelId = Uuid.random()
        val groupId = Uuid.random()
        val permission = ChatChannelPermission(channelId = channelId, groupId = groupId, action = PermissionAction.VIEW)
        assertEquals(channelId, permission.entityId)
        assertEquals(channelId, permission.channelId)
    }

    @Test
    fun `ChatChannelPermission stores groupId and action`() {
        val channelId = Uuid.random()
        val groupId = Uuid.random()
        val permission = ChatChannelPermission(channelId = channelId, groupId = groupId, action = PermissionAction.EDIT)
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.EDIT, permission.action)
    }
}
