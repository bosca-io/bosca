package bosca.security.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PermissionTest {

    private val groupId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val perm = Permission(groupId = groupId, action = PermissionAction.VIEW)
        assertEquals(groupId, perm.groupId)
        assertEquals(PermissionAction.VIEW, perm.action)
    }

    @Test
    fun entityIdThrowsUnsupported() {
        val perm = Permission(groupId = groupId, action = PermissionAction.EDIT)
        assertFailsWith<UnsupportedOperationException> {
            perm.entityId
        }
    }

    @Test
    fun dataClassEquality() {
        val a = Permission(groupId = groupId, action = PermissionAction.MANAGE)
        val b = Permission(groupId = groupId, action = PermissionAction.MANAGE)
        assertEquals(a, b)
    }
}
