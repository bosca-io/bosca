package bosca.content.collection.model

import bosca.security.model.PermissionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class CollectionPermissionTest {

    @Test
    fun `entityId equals collectionId`() {
        val collectionId = Uuid.random()
        val groupId = Uuid.random()
        val permission = CollectionPermission(
            collectionId = collectionId,
            groupId = groupId,
            action = PermissionAction.VIEW
        )
        assertEquals(collectionId, permission.entityId)
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.VIEW, permission.action)
    }

    @Test
    fun `different actions are stored correctly`() {
        val id = Uuid.random()
        val permission = CollectionPermission(
            collectionId = id,
            groupId = Uuid.random(),
            action = PermissionAction.EDIT
        )
        assertEquals(PermissionAction.EDIT, permission.action)
    }
}
