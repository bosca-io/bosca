package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface SuppressionListRepository {

    @Query("select exists(select 1 from communications.suppression_list where email = :email)")
    suspend fun isSuppressed(email: String): Boolean

    @Query("""
        insert into communications.suppression_list (email, reason, provider_code)
        values (:email, :reason, :providerCode)
        on conflict (email) do nothing
    """)
    suspend fun add(email: String, reason: String, providerCode: String?)
}
