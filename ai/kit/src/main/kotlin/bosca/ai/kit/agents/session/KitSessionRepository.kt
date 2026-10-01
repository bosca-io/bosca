package bosca.ai.kit.agents.session

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * The `kit.checkpoint` index — KSP generates the JDBC implementation. Checkpoints are listed and the
 * latest found by `session_id` (the run); a whole chat session's checkpoints (parent + sub-agent runs)
 * are removable by `parent_session_id`.
 */
@Repository
interface KitSessionRepository {

    /** Append a checkpoint index row (the bytes are written to object storage separately). */
    @Query("insert into kit.checkpoint (parent_session_id, session_id, checkpoint_id, version) values (:parentSessionId, :sessionId, :checkpointId, :version)")
    suspend fun add(checkpoint: KitSessionCheckpoint)

    /** All checkpoints for the run [sessionId], oldest first. */
    @Query("select * from kit.checkpoint where session_id = :sessionId order by version")
    suspend fun bySession(sessionId: UUID): List<KitSessionCheckpoint>

    /** The most recent checkpoint for the run [sessionId], or null if it has none. */
    @Query("select * from kit.checkpoint where session_id = :sessionId order by version desc limit 1")
    suspend fun latestBySession(sessionId: UUID): KitSessionCheckpoint?

    /** Checkpoints for the run [sessionId] older than [version] — superseded, returned so their blobs can be dropped. */
    @Query("select * from kit.checkpoint where session_id = :sessionId and version < :version")
    suspend fun supersededBySession(sessionId: UUID, version: Long): List<KitSessionCheckpoint>

    /** Remove the run [sessionId]'s checkpoints older than [version] (only the latest is ever restored). */
    @Query("delete from kit.checkpoint where session_id = :sessionId and version < :version")
    suspend fun deleteSupersededBySession(sessionId: UUID, version: Long)

    /** Every checkpoint owned by the chat session [parentSessionId] (its parent + sub-agent runs) — for GC. */
    @Query("select * from kit.checkpoint where parent_session_id = :parentSessionId")
    suspend fun byParentSession(parentSessionId: UUID): List<KitSessionCheckpoint>

    /** Remove every checkpoint owned by the chat session [parentSessionId] (its parent + sub-agent runs). */
    @Query("delete from kit.checkpoint where parent_session_id = :parentSessionId")
    suspend fun deleteByParentSession(parentSessionId: UUID)
}
