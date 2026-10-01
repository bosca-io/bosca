package bosca.profile.organization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.organization.model.OrganizationDomain
import bosca.profile.organization.model.OrganizationMember
import bosca.serialization.UUID

@Repository
interface OrganizationDomainsRepository {

    @Query("select * from organization_domains where organization_id = :id")
    suspend fun getAll(id: UUID): List<OrganizationDomain>

    @Query("select * from organization_domains where domain = :domain")
    suspend fun getDomain(domain: String): OrganizationDomain?

    @Query("insert into organization_domains (organization_id, domain, auto_join, group_id) values (:organizationId, :domain, :autoJoin, :groupId) returning *")
    suspend fun add(domain: OrganizationDomain): OrganizationDomain

    @Query("delete from organization_domains where organization_id = :organizationId and domain = :domain")
    suspend fun delete(organizationId: UUID, domain: String)

    @Query("delete from organization_domains where organization_id = :organizationId")
    suspend fun deleteAll(organizationId: UUID)
}