package bosca.postgres.admin.pgbouncer

import bosca.db.ConnectionConfig
import bosca.db.DatabaseDispatcher
import bosca.postgres.admin.model.PgBouncerDatabase
import bosca.postgres.admin.model.PgBouncerDatabaseStats
import bosca.postgres.admin.model.PgBouncerPoolStats
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Communicates with PgBouncer's lightweight admin console by connecting to the
 * special `pgbouncer` virtual database on the same host/port as the primary
 * database connection. This auto-detects whether PgBouncer is in the connection
 * path — if the primary connection goes through PgBouncer, the admin console is
 * reachable; if it's a direct PostgreSQL connection, the connect attempt simply
 * fails and the client reports PgBouncer as unavailable.
 *
 * Each method opens a short-lived JDBC connection (no pooling needed since the
 * admin console is inherently single-threaded and low-traffic) with a 3-second
 * connect timeout, maps the resulting rows to domain models, and closes the
 * connection in a `finally` block. All operations dispatch onto [DatabaseDispatcher]
 * and return empty/null on failure.
 */
class PgBouncerClient(primaryConfig: ConnectionConfig) {

    private val log = LoggerFactory.getLogger(PgBouncerClient::class.java)

    private val adminUrl: String = deriveAdminUrl(primaryConfig.url)
    private val user: String = primaryConfig.user
    private val password: String = primaryConfig.password

    /**
     * Retrieves the PgBouncer version string by executing `SHOW VERSION` against
     * the admin console.
     *
     * @return the version string (e.g. "PgBouncer 1.22.0"), or null if unreachable
     */
    suspend fun getVersion(): String? = withContext(DatabaseDispatcher) {
        try {
            executeQuery("SHOW VERSION") { rs ->
                rs.getString(1)
            }.firstOrNull()
        } catch (e: SQLException) {
            log.debug("PgBouncer not available on this connection", e)
            null
        }
    }

    /**
     * Retrieves per-database/user connection pool utilization from PgBouncer's
     * `SHOW POOLS` command, showing how client connections are distributed across
     * server connections and whether clients are queued.
     */
    suspend fun getPools(): List<PgBouncerPoolStats> = withContext(DatabaseDispatcher) {
        try {
            executeQuery("SHOW POOLS") { rs ->
                PgBouncerPoolStats(
                    database = rs.getString("database"),
                    user = rs.getString("user"),
                    clActive = rs.getInt("cl_active"),
                    clWaiting = rs.getInt("cl_waiting"),
                    clCancelReq = rs.getInt("cl_cancel_req"),
                    clActiveCancelReq = rs.getInt("cl_active_cancel_req"),
                    svActive = rs.getInt("sv_active"),
                    svActiveCancel = rs.getInt("sv_active_cancel"),
                    svBeingCanceled = rs.getInt("sv_being_canceled"),
                    svIdle = rs.getInt("sv_idle"),
                    svUsed = rs.getInt("sv_used"),
                    svTested = rs.getInt("sv_tested"),
                    svLogin = rs.getInt("sv_login"),
                    maxwait = rs.getLong("maxwait"),
                    maxwaitUs = rs.getLong("maxwait_us"),
                    poolMode = rs.getString("pool_mode"),
                )
            }
        } catch (e: SQLException) {
            log.warn("Failed to get PgBouncer pools", e)
            emptyList()
        }
    }

    /**
     * Retrieves aggregated throughput and latency statistics from PgBouncer's
     * `SHOW STATS` command, providing per-database insight into transaction rates,
     * data volumes, and timing.
     */
    suspend fun getStats(): List<PgBouncerDatabaseStats> = withContext(DatabaseDispatcher) {
        try {
            executeQuery("SHOW STATS") { rs ->
                PgBouncerDatabaseStats(
                    database = rs.getString("database"),
                    totalXactCount = rs.getLong("total_xact_count"),
                    totalQueryCount = rs.getLong("total_query_count"),
                    totalReceived = rs.getLong("total_received"),
                    totalSent = rs.getLong("total_sent"),
                    totalXactTime = rs.getLong("total_xact_time"),
                    totalQueryTime = rs.getLong("total_query_time"),
                    totalWaitTime = rs.getLong("total_wait_time"),
                    avgXactCount = rs.getLong("avg_xact_count"),
                    avgQueryCount = rs.getLong("avg_query_count"),
                    avgRecv = rs.getLong("avg_recv"),
                    avgSent = rs.getLong("avg_sent"),
                    avgXactTime = rs.getLong("avg_xact_time"),
                    avgQueryTime = rs.getLong("avg_query_time"),
                    avgWaitTime = rs.getLong("avg_wait_time"),
                )
            }
        } catch (e: SQLException) {
            log.warn("Failed to get PgBouncer stats", e)
            emptyList()
        }
    }

    /**
     * Retrieves database routing configuration from PgBouncer's `SHOW DATABASES`
     * command, showing which logical database names map to which physical hosts
     * and their pool sizing.
     */
    suspend fun getDatabases(): List<PgBouncerDatabase> = withContext(DatabaseDispatcher) {
        try {
            executeQuery("SHOW DATABASES") { rs ->
                PgBouncerDatabase(
                    name = rs.getString("name"),
                    host = rs.getString("host"),
                    port = rs.getInt("port"),
                    database = rs.getString("database"),
                    forceUser = rs.getString("force_user"),
                    poolSize = rs.getInt("pool_size"),
                    minPoolSize = rs.getInt("min_pool_size"),
                    reservePool = rs.getInt("reserve_pool"),
                    poolMode = rs.getString("pool_mode"),
                    maxConnections = rs.getInt("max_connections"),
                    currentConnections = rs.getInt("current_connections"),
                    paused = rs.getInt("paused") != 0,
                    disabled = rs.getInt("disabled") != 0,
                )
            }
        } catch (e: SQLException) {
            log.warn("Failed to get PgBouncer databases", e)
            emptyList()
        }
    }

    private fun <T> executeQuery(sql: String, mapper: (ResultSet) -> T): List<T> {
        val connection = DriverManager.getConnection(adminUrl, user, password)
        try {
            val stmt = connection.createStatement()
            try {
                val rs = stmt.executeQuery(sql)
                val results = mutableListOf<T>()
                while (rs.next()) {
                    results.add(mapper(rs))
                }
                return results
            } finally {
                stmt.close()
            }
        } finally {
            connection.close()
        }
    }

    companion object {

        /**
         * Derives the PgBouncer admin console URL from the primary database URL
         * by replacing the database name with `pgbouncer`, preserving existing
         * query parameters (such as SSL settings), and ensuring a short connect
         * timeout is present. For example:
         * `jdbc:postgresql://host:5432/mydb?sslmode=require` →
         * `jdbc:postgresql://host:5432/pgbouncer?sslmode=require&connectTimeout=3`
         */
        internal fun deriveAdminUrl(primaryUrl: String): String {
            val base = primaryUrl.substringBefore("?")
            val existingQuery = primaryUrl.substringAfter("?", "")
            val schemeEnd = base.indexOf("://")
            if (schemeEnd < 0) return "$base/pgbouncer?connectTimeout=3"
            val pathStart = base.indexOf('/', schemeEnd + 3)
            val replaced = if (pathStart < 0) {
                "$base/pgbouncer"
            } else {
                base.substring(0, pathStart + 1) + "pgbouncer"
            }
            val params = mutableMapOf<String, String>()
            if (existingQuery.isNotEmpty()) {
                for (param in existingQuery.split("&")) {
                    val eqIdx = param.indexOf('=')
                    if (eqIdx > 0) {
                        params[param.substring(0, eqIdx)] = param.substring(eqIdx + 1)
                    }
                }
            }
            params["connectTimeout"] = "3"
            return "$replaced?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        }
    }
}
