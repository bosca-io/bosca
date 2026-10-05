package bosca.content.metadata.model

import bosca.security.model.PermissionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MetadataPermissionTest {

    private val metaId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val grpId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val perm = MetadataPermission(
            metadataId = metaId,
            groupId = grpId,
            action = PermissionAction.VIEW
        )
        assertEquals(metaId, perm.metadataId)
        assertEquals(grpId, perm.groupId)
        assertEquals(PermissionAction.VIEW, perm.action)
    }

    @Test
    fun entityIdReturnsMetadataId() {
        val perm = MetadataPermission(
            metadataId = metaId,
            groupId = grpId,
            action = PermissionAction.EDIT
        )
        assertEquals(metaId, perm.entityId)
    }

    @Test
    fun dataClassEquality() {
        val a = MetadataPermission(metadataId = metaId, groupId = grpId, action = PermissionAction.VIEW)
        val b = MetadataPermission(metadataId = metaId, groupId = grpId, action = PermissionAction.VIEW)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
