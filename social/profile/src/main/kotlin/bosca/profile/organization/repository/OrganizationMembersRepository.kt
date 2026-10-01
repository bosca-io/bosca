package bosca.profile.organization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.organization.model.OrganizationMember
import bosca.serialization.UUID

@Repository
interface OrganizationMembersRepository {

    @Query("select * from organization_members where organization_id = :id order by principal_id offset :offset limit :limit")
    suspend fun getAll(id: UUID, offset: Long, limit: Int): List<OrganizationMember>

    @Query("select count(*) from organization_members where organization_id = :id")
    suspend fun getCount(id: UUID): Long

    @Query("insert into organization_members (organization_id, principal_id, created) values (:organizationId, :principalId, now()) returning *")
    suspend fun add(member: OrganizationMember): OrganizationMember

    @Query("delete from organization_members where organization_id = :organizationId and principal_id = :principalId")
    suspend fun delete(member: OrganizationMember)

    @Query("select * from organization_members where principal_id = any(:ids)")
    suspend fun getOrganizationsByPrincipals(ids: List<UUID>): List<OrganizationMember>
}