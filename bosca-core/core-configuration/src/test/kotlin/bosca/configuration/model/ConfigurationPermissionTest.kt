package bosca.configuration.model

import bosca.security.model.PermissionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ConfigurationPermissionTest {

    @Test
    fun `ConfigurationPermission stores configurationId, action, and groupId`() {
        val configId = Uuid.random()
        val groupId = Uuid.random()
        val permission = ConfigurationPermission(
            configurationId = configId,
            action = PermissionAction.VIEW,
            groupId = groupId
        )
        assertEquals(configId, permission.configurationId)
        assertEquals(PermissionAction.VIEW, permission.action)
        assertEquals(groupId, permission.groupId)
    }

    @Test
    fun `ConfigurationPermission entityId returns configurationId`() {
        val configId = Uuid.random()
        val permission = ConfigurationPermission(
            configurationId = configId,
            action = PermissionAction.EDIT,
            groupId = Uuid.random()
        )
        assertEquals(configId, permission.entityId)
    }

    @Test
    fun `ConfigurationPermission with MANAGE action`() {
        val permission = ConfigurationPermission(
            configurationId = Uuid.random(),
            action = PermissionAction.MANAGE,
            groupId = Uuid.random()
        )
        assertEquals(PermissionAction.MANAGE, permission.action)
    }

    @Test
    fun `ConfigurationPermission with DELETE action`() {
        val permission = ConfigurationPermission(
            configurationId = Uuid.random(),
            action = PermissionAction.DELETE,
            groupId = Uuid.random()
        )
        assertEquals(PermissionAction.DELETE, permission.action)
    }

    @Test
    fun `ConfigurationPermission data class equality`() {
        val configId = Uuid.random()
        val groupId = Uuid.random()
        val p1 = ConfigurationPermission(configId, PermissionAction.VIEW, groupId)
        val p2 = ConfigurationPermission(configId, PermissionAction.VIEW, groupId)
        assertEquals(p1, p2)
    }
}
