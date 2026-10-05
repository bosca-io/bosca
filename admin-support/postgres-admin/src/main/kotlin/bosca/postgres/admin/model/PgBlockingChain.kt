package bosca.postgres.admin.model

import bosca.db.annotation.ColumnName

/**
 * Represents a blocking relationship between two database backends, where one process
 * holds a lock that prevents another from proceeding. The chain shows the blocker's PID,
 * query, and state alongside the blocked process's details, enabling administrators to
 * identify and resolve lock contention cascades.
 */
data class PgBlockingChain(
    @ColumnName("blocked_pid") val blockedPid: Int,
    @ColumnName("blocked_user") val blockedUser: String?,
    @ColumnName("blocked_query") val blockedQuery: String?,
    @ColumnName("blocked_state") val blockedState: String?,
    @ColumnName("blocked_duration_seconds") val blockedDurationSeconds: Float?,
    @ColumnName("blocking_pid") val blockingPid: Int,
    @ColumnName("blocking_user") val blockingUser: String?,
    @ColumnName("blocking_query") val blockingQuery: String?,
    @ColumnName("blocking_state") val blockingState: String?,
)
