package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.Group
import bosca.security.model.PrincipalGroup
import bosca.serialization.UUID

@Repository
interface PrincipalGroupRepository {

    @Query("select * from principal_groups where principal = :id")
    suspend fun getPrincipalGroupByPrincipal(id: UUID): List<PrincipalGroup>

    @Query("select * from principal_groups where group_id = :id")
    suspend fun getGroupsByPrincipalId(id: UUID): List<PrincipalGroup>

    @Query("select groups.* from principal_groups inner join groups on (principal_groups.group_id = groups.id) where principal_groups.principal = :id")
    suspend fun getPrincipalGroups(id: UUID): List<Group>

    @Query("select principal, group_id from principal_groups where principal = any(:principalIds)")
    suspend fun getPrincipalGroups(principalIds: List<UUID>): List<PrincipalGroup>

    @Query("delete from principal_groups where principal = :principalId and group_id = :groupId")
    suspend fun deleteByPrincipalAndGroupId(principalId: UUID, groupId: UUID)

    @Query("insert into principal_groups (principal, group_id) values (:principal, :groupId) returning *")
    suspend fun add(principalGroup: PrincipalGroup)
}
