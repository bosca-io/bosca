package bosca.profile.profile.model

import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ProfilePermissionTest {

    @Test
    fun `creation preserves all field values`() {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val permission = ProfilePermission(
            entityId = entityId,
            groupId = groupId,
            action = PermissionAction.VIEW
        )
        assertEquals(entityId, permission.entityId)
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.VIEW, permission.action)
    }

    @Test
    fun `implements EntityPermission interface`() {
        val permission = ProfilePermission(
            entityId = UUID.random(),
            groupId = UUID.random(),
            action = PermissionAction.EDIT
        )
        assertIs<EntityPermission>(permission)
    }

    @Test
    fun `all permission actions are accepted`() {
        PermissionAction.entries.forEach { action ->
            val permission = ProfilePermission(
                entityId = UUID.random(),
                groupId = UUID.random(),
                action = action
            )
            assertEquals(action, permission.action)
        }
    }

    @Test
    fun `different instances with same values are not equal because it is a class not data class`() {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val a = ProfilePermission(entityId = entityId, groupId = groupId, action = PermissionAction.VIEW)
        val b = ProfilePermission(entityId = entityId, groupId = groupId, action = PermissionAction.VIEW)
        // ProfilePermission is a class, not a data class, so identity equality applies
        assertNotEquals(a, b)
    }

    @Test
    fun `entityId and groupId are accessible via EntityPermission interface`() {
        val entityId = UUID.random()
        val groupId = UUID.random()
        val permission: EntityPermission = ProfilePermission(
            entityId = entityId,
            groupId = groupId,
            action = PermissionAction.MANAGE
        )
        assertEquals(entityId, permission.entityId)
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.MANAGE, permission.action)
    }
}
