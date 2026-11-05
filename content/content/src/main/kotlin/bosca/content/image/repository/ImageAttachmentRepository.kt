package bosca.content.image.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface ImageAttachmentRepository {
    /** Serializes a target's image replacements until the enclosing transaction commits or rolls back. */
    @Query("select true from pg_advisory_xact_lock(hashtextextended('content.image.attachment:' || :target, 0))")
    suspend fun lock(target: String): Boolean
}
