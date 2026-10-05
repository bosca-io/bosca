package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.communications.model.UnsubscribeToken
import bosca.serialization.UUID

@Repository
interface UnsubscribeTokenRepository {

    @Query("select * from communications.unsubscribe_tokens where token = :token")
    suspend fun get(token: String): UnsubscribeToken?

    @Query("""
        insert into communications.unsubscribe_tokens (token, profile_id, type)
        values (:token, :profileId, :type)
        returning *
    """)
    suspend fun insert(token: String, profileId: UUID, type: String?): UnsubscribeToken
}
