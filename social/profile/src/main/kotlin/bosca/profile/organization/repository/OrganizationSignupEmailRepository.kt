package bosca.profile.organization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.organization.model.OrganizationSignupEmail
import bosca.profile.organization.model.OrganizationSignupEmailInput
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.serialization.UUID

@Repository
interface OrganizationSignupEmailRepository {

    @Query("select * from organization_signup_email where organization_id = :id")
    suspend fun getAll(id: UUID): List<OrganizationSignupEmail>

    @Query("select * from organization_signup_email where email = :email")
    suspend fun getEmail(email: String): List<OrganizationSignupEmail>

    @Query("insert into organization_signup_email (organization_id, email, group_id, created, expires) values (:organizationId, :email, :groupId, :created, :expires) returning *")
    suspend fun add(token: OrganizationSignupEmail): OrganizationSignupEmail

    @Query("delete from organization_signup_email where organization_id = :organizationId and email = :email")
    suspend fun delete(organizationId: UUID, email: String)
}