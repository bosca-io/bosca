package bosca.community.repository

import bosca.community.model.CommunityGroupSignupEmail
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CommunityGroupSignupEmailRepository {

    @Query("select * from community_signup_emails where group_id = :id")
    suspend fun getAll(id: UUID): List<CommunityGroupSignupEmail>

    @Query("select * from community_signup_emails where email = :email")
    suspend fun getEmail(email: String): List<CommunityGroupSignupEmail>

    @Query("insert into community_signup_emails (email, group_id, created, expires) values (:email, :groupId, :created, :expires) returning *")
    suspend fun add(token: CommunityGroupSignupEmail): CommunityGroupSignupEmail

    @Query("delete from community_signup_emails where group_id = :groupId and email = :email")
    suspend fun delete(groupId: UUID, email: String)
}