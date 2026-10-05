package bosca.postgres.admin.model

/**
 * Database routing configuration from PgBouncer's `SHOW DATABASES` command, showing
 * which logical database names map to which physical PostgreSQL hosts and ports,
 * along with per-database pool sizing and connection limits.
 */
data class PgBouncerDatabase(
    val name: String,
    val host: String?,
    val port: Int,
    val database: String,
    val forceUser: String?,
    val poolSize: Int,
    val minPoolSize: Int,
    val reservePool: Int,
    val poolMode: String?,
    val maxConnections: Int,
    val currentConnections: Int,
    val paused: Boolean,
    val disabled: Boolean,
)
