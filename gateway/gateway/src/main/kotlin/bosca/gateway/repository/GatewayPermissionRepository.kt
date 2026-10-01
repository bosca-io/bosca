package bosca.gateway.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.gateway.model.GatewayPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID

@Repository
interface GatewayPermissionRepository {

    @Query("select * from gateway.permission where gateway_id = :gatewayId")
    suspend fun getByGatewayId(gatewayId: UUID): List<GatewayPermission>

    @Query("select * from gateway.permission where gateway_id = any(:gatewayIds)")
    suspend fun getByGatewayIds(gatewayIds: List<UUID>): List<GatewayPermission>

    @Query(
        """
        insert into gateway.permission (gateway_id, group_id, action, granted_by)
        values (:gatewayId, :groupId, :action, :grantedBy)
        on conflict (gateway_id, group_id, action) do nothing
        """
    )
    suspend fun add(gatewayId: UUID, groupId: UUID, action: PermissionAction, grantedBy: UUID)

    @Query("delete from gateway.permission where gateway_id = :gatewayId and group_id = :groupId and action = :action")
    suspend fun delete(gatewayId: UUID, groupId: UUID, action: PermissionAction)
}
