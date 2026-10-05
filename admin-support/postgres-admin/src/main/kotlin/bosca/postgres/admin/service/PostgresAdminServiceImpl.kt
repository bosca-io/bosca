package bosca.postgres.admin.service

import bosca.db.ConnectionPool
import bosca.db.DatabaseDispatcher
import bosca.db.use
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
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
import bosca.postgres.admin.pgbouncer.PgBouncerClient
import bosca.postgres.admin.repository.PostgresAdminRepository
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet

/**
 * Delegates most read-only queries to [PostgresAdminRepository] for automatic ResultSet
 * mapping, while handling operations that require custom logic (settings with enum arrays,
 * extension availability checks, DDL maintenance commands) directly.
 */
@ServiceImplementation
class PostgresAdminServiceImpl(
    private val repository: PostgresAdminRepository,
    private val pgBouncerClient: PgBouncerClient,
) : PostgresAdminService {

    private val log = LoggerFactory.getLogger(PostgresAdminServiceImpl::class.java)

    override suspend fun getDatabaseStats(): PgDatabaseStats {
        return repository.getDatabaseStats() ?: throw IllegalStateException("Expected database stats for current database")
    }

    override suspend fun getActiveQueries(minDurationSeconds: Float?): List<PgActiveQuery> {
        return if (minDurationSeconds != null) {
            repository.getActiveQueriesMinDuration(minDurationSeconds)
        } else {
            repository.getActiveQueries()
        }
    }

    override suspend fun getSlowQueries(limit: Int, orderBy: String?): List<PgSlowQuery> {
        if (repository.checkExtensionInstalled("pg_stat_statements") == null) return emptyList()
        return if (orderBy == "mean") {
            repository.getSlowQueriesByMean(limit)
        } else {
            repository.getSlowQueriesByTotal(limit)
        }
    }

    override suspend fun getTableStats(schemaName: String?): List<PgTableStats> {
        return if (schemaName != null) {
            repository.getTableStatsBySchema(schemaName)
        } else {
            repository.getTableStats()
        }
    }

    override suspend fun getIndexStats(schemaName: String?): List<PgIndexStats> {
        return if (schemaName != null) {
            repository.getIndexStatsBySchema(schemaName)
        } else {
            repository.getIndexStats()
        }
    }

    override suspend fun getUnusedIndexes(minTableSize: Long): List<PgIndexStats> {
        return repository.getUnusedIndexes(minTableSize)
    }

    override suspend fun getIndexSuggestions(minSeqScans: Long): List<PgIndexSuggestion> {
        return repository.getIndexSuggestions(minSeqScans)
    }

    override suspend fun getLocks(): List<PgLockInfo> {
        return repository.getLocks()
    }

    override suspend fun getReplicationSlots(): List<PgReplicationSlot> {
        return repository.getReplicationSlots()
    }

    @OptIn(InternalDI::class)
    override suspend fun getConnectionPoolStats(): List<PgConnectionPoolStats> {
        return ProviderRegistry.findAll(ConnectionPool::class).map {
            val pool = it.get()
            PgConnectionPoolStats(
                poolName = pool.name,
                maxConnections = pool.maxConnections,
                activeConnections = pool.activeConnections,
                createdConnections = pool.createdConnections,
                hasAvailableConnections = pool.hasAvailableConnections,
            )
        }
    }

    override suspend fun getSettings(filter: String?): List<PgSetting> = withContext(DatabaseDispatcher) {
        val filterClause: String
        val paramSetter: (java.sql.PreparedStatement) -> Unit
        if (filter != null) {
            filterClause = "WHERE name ILIKE ? OR category ILIKE ?"
            val pattern = "%$filter%"
            paramSetter = { stmt ->
                stmt.setString(1, pattern)
                stmt.setString(2, pattern)
            }
        } else {
            filterClause = "WHERE category IN (?, ?, ?, ?, ?, ?, ?, ?)"
            paramSetter = { stmt ->
                val categories = listOf(
                    "Autovacuum", "Resource Usage / Memory", "Query Tuning / Planner Cost Constants",
                    "Write-Ahead Log / Settings", "Connections and Authentication / Connection Settings",
                    "Resource Usage / Disk", "Query Tuning / Other Planner Options", "Statistics / Monitoring",
                )
                categories.forEachIndexed { index, category -> stmt.setString(index + 1, category) }
            }
        }
        executeQuery(
            """
            SELECT
                name,
                setting,
                unit,
                category,
                short_desc,
                context,
                vartype,
                source,
                min_val,
                max_val,
                enumvals::text[] AS enum_vals,
                pending_restart
            FROM pg_settings
            $filterClause
            ORDER BY category, name
            """.trimIndent(),
            paramSetter,
        ) { rs ->
            val enumValsArray = rs.getArray("enum_vals")
            PgSetting(
                name = rs.getString("name"),
                setting = rs.getString("setting"),
                unit = rs.getString("unit"),
                category = rs.getString("category"),
                shortDesc = rs.getString("short_desc"),
                context = rs.getString("context"),
                vartype = rs.getString("vartype"),
                source = rs.getString("source"),
                minVal = rs.getString("min_val"),
                maxVal = rs.getString("max_val"),
                enumVals = enumValsArray?.let { arr ->
                    @Suppress("UNCHECKED_CAST")
                    (arr.array as? Array<String>)?.toList()
                },
                pendingRestart = rs.getBoolean("pending_restart"),
            )
        }
    }

    override suspend fun getVacuumProgress(): List<PgVacuumProgress> {
        return repository.getVacuumProgress()
    }

    override suspend fun getTableIOStats(schemaName: String?): List<PgTableIOStats> {
        return if (schemaName != null) {
            repository.getTableIOStatsBySchema(schemaName)
        } else {
            repository.getTableIOStats()
        }
    }

    override suspend fun getBloatedTables(minBloatPercent: Double): List<PgTableStats> {
        return repository.getBloatedTables(minBloatPercent)
    }

    override suspend fun getServerVersion(): String {
        return repository.getServerVersion() ?: throw IllegalStateException("Expected server version")
    }

    override suspend fun getUptime(): String {
        return repository.getUptime() ?: throw IllegalStateException("Expected uptime")
    }

    override suspend fun cancelQuery(pid: Int): Boolean {
        val result = repository.cancelQuery(pid) ?: false
        log.info("Cancel query requested for PID {} (result={})", pid, result)
        return result
    }

    override suspend fun terminateBackend(pid: Int): Boolean {
        val result = repository.terminateBackend(pid) ?: false
        log.info("Terminate backend requested for PID {} (result={})", pid, result)
        return result
    }

    override suspend fun analyzeTable(schemaName: String, tableName: String): Boolean = withContext(DatabaseDispatcher) {
        val identifier = "${quoteIdentifier(schemaName)}.${quoteIdentifier(tableName)}"
        val pool = provide<ConnectionPool>()
        val connection = pool.connection()
        try {
            connection.use { mgr ->
                mgr.useStatement("ANALYZE $identifier") { stmt ->
                    stmt.execute()
                }
            }
            log.info("Analyzed table {}", identifier)
            true
        } catch (e: Exception) {
            log.error("Failed to analyze table $identifier", e)
            false
        }
    }

    override suspend fun resetStatStatements(): Boolean {
        if (repository.checkExtensionInstalled("pg_stat_statements") == null) return false
        return try {
            repository.resetStatStatements()
            log.info("Reset pg_stat_statements statistics")
            true
        } catch (e: Exception) {
            log.error("Failed to reset pg_stat_statements", e)
            false
        }
    }

    override suspend fun getWalStats(): PgWalStats {
        return repository.getWalStats() ?: throw IllegalStateException("Expected WAL stats")
    }

    override suspend fun getCheckpointStats(): PgCheckpointStats {
        return repository.getCheckpointStats() ?: throw IllegalStateException("Expected checkpoint stats")
    }

    override suspend fun getBlockingChains(): List<PgBlockingChain> {
        return repository.getBlockingChains()
    }

    override suspend fun getLongRunningTransactions(minDurationSeconds: Float): List<PgLongTransaction> {
        return repository.getLongRunningTransactions(minDurationSeconds)
    }

    override suspend fun getSequenceUsage(minPercentUsed: Float): List<PgSequenceUsage> {
        return repository.getSequenceUsage(minPercentUsed)
    }

    override suspend fun getDatabaseSizeBreakdown(limit: Int): List<PgObjectSize> {
        return repository.getDatabaseSizeBreakdown(limit)
    }

    override suspend fun getInstalledExtensions(): List<PgExtension> {
        return repository.getInstalledExtensions()
    }

    override suspend fun vacuumTable(schemaName: String, tableName: String, full: Boolean): Boolean = withContext(DatabaseDispatcher) {
        val identifier = "${quoteIdentifier(schemaName)}.${quoteIdentifier(tableName)}"
        val vacuumType = if (full) "VACUUM FULL" else "VACUUM"
        val pool = provide<ConnectionPool>()
        val connection = pool.connection()
        try {
            connection.use { mgr ->
                mgr.useStatement("$vacuumType $identifier") { stmt ->
                    stmt.execute()
                }
            }
            log.info("Vacuumed table {} (full={})", identifier, full)
            true
        } catch (e: Exception) {
            log.error("Failed to vacuum table $identifier", e)
            false
        }
    }

    override suspend fun reindex(schemaName: String, indexName: String): Boolean = withContext(DatabaseDispatcher) {
        val identifier = "${quoteIdentifier(schemaName)}.${quoteIdentifier(indexName)}"
        val pool = provide<ConnectionPool>()
        val connection = pool.connection()
        try {
            connection.use { mgr ->
                mgr.useStatement("REINDEX INDEX $identifier") { stmt ->
                    stmt.execute()
                }
            }
            log.info("Reindexed {}", identifier)
            true
        } catch (e: Exception) {
            log.error("Failed to reindex $identifier", e)
            false
        }
    }

    override suspend fun getPgBouncerInfo(): PgBouncerInfo {
        val version = pgBouncerClient.getVersion()
            ?: return PgBouncerInfo(
                available = false,
                version = null,
                pools = emptyList(),
                stats = emptyList(),
                databases = emptyList(),
            )
        return PgBouncerInfo(
            available = true,
            version = version,
            pools = pgBouncerClient.getPools(),
            stats = pgBouncerClient.getStats(),
            databases = pgBouncerClient.getDatabases(),
        )
    }

    override suspend fun getReplicationStatus(): List<PgReplicationStatus> {
        return repository.getReplicationStatus()
    }

    override suspend fun isInRecovery(): Boolean {
        return repository.isInRecovery() ?: false
    }

    /**
     * Validates and quotes a SQL identifier for safe use in DDL statements where parameterized
     * queries cannot be used. Uses a whitelist of allowed characters as the primary defense
     * against injection, with double-quote escaping as a secondary safety layer.
     */
    private fun quoteIdentifier(identifier: String): String {
        require(identifier.isNotBlank()) { "SQL identifier must not be blank" }
        require(SAFE_IDENTIFIER.matches(identifier)) { "SQL identifier contains invalid characters" }
        return "\"${identifier.replace("\"", "\"\"")}\""
    }

    companion object {
        private val SAFE_IDENTIFIER = Regex("^[a-zA-Z_][a-zA-Z0-9_\$]*$")
        private const val DDL_TIMEOUT_MS = 600_000L
    }

    /**
     * Executes a raw SQL query with manual ResultSet mapping, used only for queries that
     * cannot be handled by the repository pattern (e.g., settings with PostgreSQL array types).
     */
    private suspend fun <T> executeQuery(
        sql: String,
        paramSetter: (java.sql.PreparedStatement) -> Unit = {},
        mapper: (ResultSet) -> T,
    ): List<T> {
        val pool = provide<ConnectionPool>()
        val connection = pool.connection()
        return try {
            connection.use { mgr ->
                mgr.useReadOnlyStatement(sql) { stmt ->
                    paramSetter(stmt)
                    stmt.executeQuery().use { rs ->
                        val results = mutableListOf<T>()
                        while (rs.next()) {
                            results.add(mapper(rs))
                        }
                        results
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error executing admin query", e)
            throw e
        }
    }
}
