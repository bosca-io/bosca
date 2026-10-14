package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository

@Repository
interface WorkOpsGuidanceRepository {
    /** Serializes guidance writes, including project writes that depend on the platform revision. */
    @Query("select true from pg_advisory_xact_lock(hashtextextended('workops.guidance', 0))")
    suspend fun lock(): Boolean
}
