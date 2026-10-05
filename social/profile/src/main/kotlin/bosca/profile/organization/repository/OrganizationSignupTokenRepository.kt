package bosca.profile.organization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.serialization.UUID

@Repository
interface OrganizationSignupTokenRepository {

    @Query("select * from organization_signup_tokens where organization_id = :id")
    suspend fun getAll(id: UUID): List<OrganizationSignupToken>

    @Query("select * from organization_signup_tokens where token = :token")
    suspend fun getToken(token: String): OrganizationSignupToken?

    @Query("insert into organization_signup_tokens (organization_id, token, group_id, created, expires) values (:organizationId, :token, :groupId, :created, :expires) returning *")
    suspend fun add(token: OrganizationSignupToken): OrganizationSignupToken

    @Query("delete from organization_signup_tokens where organization_id = :organizationId and token = :token")
    suspend fun delete(organizationId: UUID, token: String)
}