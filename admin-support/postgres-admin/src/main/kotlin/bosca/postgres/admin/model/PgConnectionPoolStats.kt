package bosca.postgres.admin.model

/**
 * Statistics for an application-level database connection pool, exposing the pool's
 * capacity, current utilization, and availability to monitor for connection exhaustion
 * or oversized pool configurations.
 */
data class PgConnectionPoolStats(
    val poolName: String,
    val maxConnections: Int,
    val activeConnections: Int,
    val createdConnections: Int,
    val hasAvailableConnections: Boolean,
)
