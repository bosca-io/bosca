package bosca.postgres.admin.model

/**
 * Aggregated throughput and latency statistics from PgBouncer's `SHOW STATS` command,
 * providing per-database insight into transaction and query rates, data transfer
 * volumes, and average timing to assess connection pooler efficiency.
 */
data class PgBouncerDatabaseStats(
    val database: String,
    val totalXactCount: Long,
    val totalQueryCount: Long,
    val totalReceived: Long,
    val totalSent: Long,
    val totalXactTime: Long,
    val totalQueryTime: Long,
    val totalWaitTime: Long,
    val avgXactCount: Long,
    val avgQueryCount: Long,
    val avgRecv: Long,
    val avgSent: Long,
    val avgXactTime: Long,
    val avgQueryTime: Long,
    val avgWaitTime: Long,
)
