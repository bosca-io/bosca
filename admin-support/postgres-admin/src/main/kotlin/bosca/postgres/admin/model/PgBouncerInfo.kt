package bosca.postgres.admin.model

/**
 * Composite view of PgBouncer status combining availability, version information,
 * pool utilization, throughput statistics, and database routing configuration into
 * a single snapshot for the admin dashboard. Returns [available] as false when
 * PgBouncer is configured but unreachable.
 */
data class PgBouncerInfo(
    val available: Boolean,
    val version: String?,
    val pools: List<PgBouncerPoolStats>,
    val stats: List<PgBouncerDatabaseStats>,
    val databases: List<PgBouncerDatabase>,
)
