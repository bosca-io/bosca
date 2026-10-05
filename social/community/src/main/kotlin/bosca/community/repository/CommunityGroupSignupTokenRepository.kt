package bosca.community.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.community.model.CommunityGroupSignupToken
import bosca.serialization.UUID

@Repository
interface CommunityGroupSignupTokenRepository {

    @Query("select * from community_group_signup_tokens where group_id = :id")
    suspend fun getAll(id: UUID): List<CommunityGroupSignupToken>

    @Query("select * from community_group_signup_tokens where token = :token")
    suspend fun getToken(token: String): CommunityGroupSignupToken?

    @Query("insert into community_group_signup_tokens (group_id, token, created, expires) values (:groupId, :token, :created, :expires) returning *")
    suspend fun add(token: CommunityGroupSignupToken): CommunityGroupSignupToken

    @Query("delete from community_group_signup_tokens where group_id = :groupId and token = :token")
    suspend fun delete(groupId: UUID, token: String)
}
