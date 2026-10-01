package bosca.postgres.admin.service

import bosca.postgres.admin.model.PgActiveQuery
import bosca.postgres.admin.model.PgBlockingChain
import bosca.postgres.admin.model.PgCheckpointStats
import bosca.postgres.admin.model.PgConnectionPoolStats
import bosca.postgres.admin.model.PgDatabaseStats
import bosca.postgres.admin.model.PgExtension
import bosca.postgres.admin.model.PgIndexStats
import bosca.postgres.admin.model.PgIndexSuggestion
import bosca.postgres.admin.model.PgLockInfo
import bosca.postgres.admin.model.PgLongTransaction
import bosca.postgres.admin.model.PgObjectSize
import bosca.postgres.admin.model.PgBouncerInfo
import bosca.postgres.admin.model.PgReplicationSlot
import bosca.postgres.admin.model.PgReplicationStatus
import bosca.postgres.admin.model.PgSequenceUsage
import bosca.postgres.admin.model.PgSetting
import bosca.postgres.admin.model.PgSlowQuery
import bosca.postgres.admin.model.PgTableIOStats
import bosca.postgres.admin.model.PgTableStats
import bosca.postgres.admin.model.PgVacuumProgress
import bosca.postgres.admin.model.PgWalStats
import bosca.service.Service

/**
 * Provides introspection into PostgreSQL's operational state by querying system
 * catalog views (`pg_stat_database`, `pg_stat_activity`, `pg_stat_user_tables`,
 * `pg_stat_user_indexes`, `pg_locks`, `pg_settings`, etc.) and the optional
 * `pg_stat_statements` extension. Exposes database health metrics, query performance
 * analysis, index recommendations, lock monitoring, and maintenance operations
 * to administrators.
 */
interface PostgresAdminService : Service {

    /**
     * Retrieves overall database statistics from `pg_stat_database` for the connected database,
     * including transaction counts, I/O activity, cache hit ratio, and size information.
     */
    suspend fun getDatabaseStats(): PgDatabaseStats

    /**
     * Retrieves currently active queries from `pg_stat_activity`, optionally filtered to
     * only include queries running longer than the specified duration.
     *
     * @param minDurationSeconds minimum query duration in seconds to include, or null for all
     */
    suspend fun getActiveQueries(minDurationSeconds: Float? = null): List<PgActiveQuery>

    /**
     * Retrieves the slowest queries by execution time from `pg_stat_statements`.
     * Returns an empty list if the extension is not installed.
     *
     * @param limit maximum number of queries to return
     * @param orderBy column to order by: "total" for total time, "mean" for mean time
     */
    suspend fun getSlowQueries(limit: Int = 20, orderBy: String? = "total"): List<PgSlowQuery>

    /**
     * Retrieves per-table statistics from `pg_stat_user_tables` combined with table size
     * information, optionally filtered to a specific schema.
     *
     * @param schemaName schema to filter by, or null for all non-system schemas
     */
    suspend fun getTableStats(schemaName: String? = null): List<PgTableStats>

    /**
     * Retrieves per-index statistics from `pg_stat_user_indexes` combined with index size
     * and definition, optionally filtered to a specific schema.
     *
     * @param schemaName schema to filter by, or null for all non-system schemas
     */
    suspend fun getIndexStats(schemaName: String? = null): List<PgIndexStats>

    /**
     * Identifies indexes that have never been used or are rarely scanned, which may be
     * candidates for removal to save storage and reduce write overhead.
     *
     * @param minTableSize minimum table size in bytes to consider
     */
    suspend fun getUnusedIndexes(minTableSize: Long = 0): List<PgIndexStats>

    /**
     * Identifies tables with high sequential scan counts relative to index scans,
     * suggesting that adding appropriate indexes could improve performance.
     *
     * @param minSeqScans minimum number of sequential scans to consider a table
     */
    suspend fun getIndexSuggestions(minSeqScans: Long = 50): List<PgIndexSuggestion>

    /**
     * Retrieves current lock information from `pg_locks` joined with `pg_stat_activity`,
     * showing both granted locks and lock waiters.
     */
    suspend fun getLocks(): List<PgLockInfo>

    /**
     * Retrieves replication slot metadata from `pg_replication_slots`, including
     * activity status and retained WAL size.
     */
    suspend fun getReplicationSlots(): List<PgReplicationSlot>

    /**
     * Retrieves statistics for all application-level database connection pools,
     * showing capacity and utilization.
     */
    suspend fun getConnectionPoolStats(): List<PgConnectionPoolStats>

    /**
     * Retrieves PostgreSQL server configuration settings from `pg_settings`,
     * optionally filtered by name or category pattern.
     *
     * @param filter substring to match against setting name or category
     */
    suspend fun getSettings(filter: String? = null): List<PgSetting>

    /**
     * Retrieves progress information for currently running vacuum operations
     * from `pg_stat_progress_vacuum`.
     */
    suspend fun getVacuumProgress(): List<PgVacuumProgress>

    /**
     * Retrieves per-table I/O statistics from `pg_statio_user_tables` showing
     * disk reads versus cache hits, optionally filtered to a specific schema.
     *
     * @param schemaName schema to filter by, or null for all non-system schemas
     */
    suspend fun getTableIOStats(schemaName: String? = null): List<PgTableIOStats>

    /**
     * Retrieves tables with dead tuple ratios exceeding the specified threshold,
     * indicating tables that may benefit from a manual vacuum.
     *
     * @param minBloatPercent minimum dead-to-live tuple ratio percentage to include
     */
    suspend fun getBloatedTables(minBloatPercent: Double = 10.0): List<PgTableStats>

    /**
     * Retrieves the PostgreSQL server version string.
     */
    suspend fun getServerVersion(): String

    /**
     * Retrieves the server uptime as a human-readable string.
     */
    suspend fun getUptime(): String

    /**
     * Sends a cancel signal to the backend with the specified process ID.
     * This cancels the currently running query without terminating the connection.
     *
     * @param pid the process ID of the backend to cancel
     * @return true if the cancel signal was sent successfully
     */
    suspend fun cancelQuery(pid: Int): Boolean

    /**
     * Terminates the backend process with the specified process ID.
     * This forcefully closes the connection.
     *
     * @param pid the process ID of the backend to terminate
     * @return true if the terminate signal was sent successfully
     */
    suspend fun terminateBackend(pid: Int): Boolean

    /**
     * Runs ANALYZE on a specific table to update the query planner's statistics,
     * which can improve query plan selection.
     *
     * @param schemaName the schema containing the table
     * @param tableName the table to analyze
     */
    suspend fun analyzeTable(schemaName: String, tableName: String): Boolean

    /**
     * Resets the `pg_stat_statements` statistics counters.
     * Requires the extension to be installed.
     */
    suspend fun resetStatStatements(): Boolean

    /**
     * Retrieves Write-Ahead Log statistics from `pg_stat_wal`, including WAL generation
     * rate, buffer full events, and sync timing to help tune WAL configuration.
     */
    suspend fun getWalStats(): PgWalStats

    /**
     * Retrieves background writer and checkpoint statistics from `pg_stat_bgwriter`,
     * showing checkpoint frequency, buffer write distribution, and sync overhead.
     */
    suspend fun getCheckpointStats(): PgCheckpointStats

    /**
     * Retrieves blocking query chains by identifying which backends are blocked by
     * which other backends using `pg_blocking_pids()`, showing the full blocker-to-blocked
     * relationship with associated queries.
     */
    suspend fun getBlockingChains(): List<PgBlockingChain>

    /**
     * Retrieves transactions that have been open longer than the specified duration,
     * particularly `idle in transaction` sessions that hold locks and prevent autovacuum.
     *
     * @param minDurationSeconds minimum transaction duration in seconds to include
     */
    suspend fun getLongRunningTransactions(minDurationSeconds: Float = 60f): List<PgLongTransaction>

    /**
     * Retrieves sequence utilization information showing how close each sequence is
     * to its maximum value, helping detect sequences at risk of exhaustion.
     *
     * @param minPercentUsed minimum percentage used to include in results
     */
    suspend fun getSequenceUsage(minPercentUsed: Float = 0f): List<PgSequenceUsage>

    /**
     * Retrieves the largest database objects (tables and indexes) ranked by total size,
     * providing a storage breakdown to identify the biggest consumers of disk space.
     *
     * @param limit maximum number of objects to return
     */
    suspend fun getDatabaseSizeBreakdown(limit: Int = 50): List<PgObjectSize>

    /**
     * Retrieves all installed PostgreSQL extensions with their current and default
     * versions, useful for verifying that required extensions like `pg_stat_statements`
     * or `pgvector` are installed and up to date.
     */
    suspend fun getInstalledExtensions(): List<PgExtension>

    /**
     * Runs VACUUM on a specific table to reclaim storage occupied by dead tuples
     * and update the visibility map.
     *
     * @param schemaName the schema containing the table
     * @param tableName the table to vacuum
     * @param full whether to perform a full vacuum (rewrites the entire table, requires exclusive lock)
     */
    suspend fun vacuumTable(schemaName: String, tableName: String, full: Boolean = false): Boolean

    /**
     * Rebuilds an index to reclaim bloated space and restore optimal lookup performance.
     * Acquires an exclusive lock on the index's parent table during the rebuild.
     *
     * @param schemaName the schema containing the index
     * @param indexName the index to rebuild
     */
    suspend fun reindex(schemaName: String, indexName: String): Boolean

    /**
     * Probes the PgBouncer admin console on the same host/port as the primary
     * database connection by attempting to connect to the virtual `pgbouncer`
     * database. Returns [PgBouncerInfo] with `available = true` and pool/stats
     * data when PgBouncer is in the connection path, or `available = false`
     * when the connection goes directly to PostgreSQL.
     */
    suspend fun getPgBouncerInfo(): PgBouncerInfo

    /**
     * Retrieves streaming replication status for all connected standby servers
     * from `pg_stat_replication`, showing WAL positions, lag metrics, and
     * synchronous replication configuration.
     */
    suspend fun getReplicationStatus(): List<PgReplicationStatus>

    /**
     * Checks whether the current PostgreSQL server is running in recovery mode
     * (i.e., is a standby replica) by calling `pg_is_in_recovery()`.
     *
     * @return true if the server is a standby, false if it is the primary
     */
    suspend fun isInRecovery(): Boolean
}
