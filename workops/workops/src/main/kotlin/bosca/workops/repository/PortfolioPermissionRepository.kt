package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.model.permission.PortfolioPermission

@Repository
interface PortfolioPermissionRepository {

    @Query("insert into workops.portfolio_permissions (portfolio_id, group_id, action) values (:portfolioId, :groupId, (:action)::permission_action) on conflict do nothing")
    suspend fun add(portfolioId: UUID, groupId: UUID, action: PermissionAction)

    @Query("select * from workops.portfolio_permissions where portfolio_id = :id")
    suspend fun getByPortfolioId(id: UUID): List<PortfolioPermission>

    @Query("select * from workops.portfolio_permissions where portfolio_id = any(:ids)")
    suspend fun getByPortfolioIds(ids: List<UUID>): List<PortfolioPermission>

    @Query("delete from workops.portfolio_permissions where portfolio_id = :id and group_id = :groupId and action = (:action)::permission_action")
    suspend fun delete(id: UUID, groupId: UUID, action: PermissionAction)
}
