package bosca.ai.kit.agents.session

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * One row of the `kit.checkpoint` index — metadata only; the checkpoint bytes live in object storage.
 *
 * Two distinct ids, deliberately named apart:
 *  - [sessionId] — the run this checkpoint belongs to: the parent planner run (whose id IS the chat
 *    session id) or a sub-agent run (a per-action UUID).
 *  - [parentSessionId] — the owning **chat session** that groups them. Equals [sessionId] for the
 *    parent run; for a sub-agent run it is the chat session the run was spawned under.
 */
@Serializable
data class KitSessionCheckpoint(
    @ColumnName("parent_session_id") val parentSessionId: UUID?,
    @ColumnName("session_id") val sessionId: UUID,
    @ColumnName("checkpoint_id") val checkpointId: UUID,
    val version: Long,
)
