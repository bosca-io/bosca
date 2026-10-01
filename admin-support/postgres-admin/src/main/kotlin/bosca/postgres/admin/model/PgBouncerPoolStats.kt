package bosca.postgres.admin.model

/**
 * Connection pool utilization from PgBouncer's `SHOW POOLS` command, revealing how
 * client connections are distributed across server connections for each database/user
 * pair, and whether clients are queued waiting for available server connections.
 */
data class PgBouncerPoolStats(
    val database: String,
    val user: String,
    val clActive: Int,
    val clWaiting: Int,
    val clCancelReq: Int,
    val clActiveCancelReq: Int,
    val svActive: Int,
    val svActiveCancel: Int,
    val svBeingCanceled: Int,
    val svIdle: Int,
    val svUsed: Int,
    val svTested: Int,
    val svLogin: Int,
    val maxwait: Long,
    val maxwaitUs: Long,
    val poolMode: String,
)
