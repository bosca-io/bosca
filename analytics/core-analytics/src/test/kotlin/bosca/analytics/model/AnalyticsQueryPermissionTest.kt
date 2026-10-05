package bosca.analytics.model

import bosca.security.model.PermissionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AnalyticsQueryPermissionTest {

    // --- QueryDefinitionPermission ---

    @Test
    fun `QueryDefinitionPermission entityId stores queryId`() {
        val queryId = Uuid.random()
        val groupId = Uuid.random()
        val permission = QueryDefinitionPermission(
            entityId = queryId, groupId = groupId, action = PermissionAction.VIEW
        )
        assertEquals(queryId, permission.entityId)
        assertEquals(groupId, permission.groupId)
        assertEquals(PermissionAction.VIEW, permission.action)
    }

    // --- AnalyticsVisualizationPermission ---

    @Test
    fun `AnalyticsVisualizationPermission entityId stores visualizationId`() {
        val visId = Uuid.random()
        val groupId = Uuid.random()
        val permission = AnalyticsVisualizationPermission(
            entityId = visId, groupId = groupId, action = PermissionAction.EDIT
        )
        assertEquals(visId, permission.entityId)
        assertEquals(PermissionAction.EDIT, permission.action)
    }
}
