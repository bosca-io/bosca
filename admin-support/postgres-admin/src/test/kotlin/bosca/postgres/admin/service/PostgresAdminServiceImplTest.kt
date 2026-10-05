package bosca.postgres.admin.service

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.postgres.admin.model.PgActiveQuery
import bosca.postgres.admin.model.PgBlockingChain
import bosca.postgres.admin.model.PgCheckpointStats
import bosca.postgres.admin.model.PgDatabaseStats
import bosca.postgres.admin.model.PgExtension
import bosca.postgres.admin.model.PgIndexStats
import bosca.postgres.admin.model.PgIndexSuggestion
import bosca.postgres.admin.model.PgLockInfo
import bosca.postgres.admin.model.PgLongTransaction
import bosca.postgres.admin.model.PgObjectSize
import bosca.postgres.admin.model.PgReplicationSlot
import bosca.postgres.admin.model.PgReplicationStatus
import bosca.postgres.admin.model.PgSequenceUsage
import bosca.postgres.admin.model.PgSlowQuery
import bosca.postgres.admin.model.PgTableIOStats
import bosca.postgres.admin.model.PgTableStats
import bosca.postgres.admin.model.PgVacuumProgress
import bosca.postgres.admin.model.PgWalStats
import bosca.postgres.admin.pgbouncer.PgBouncerClient
import bosca.postgres.admin.repository.PostgresAdminRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [PostgresAdminServiceImpl] correctly delegates to [PostgresAdminRepository]
 * for read-only queries, handles conditional logic (extension checks, schema filters),
 * and manages DDL operations (ANALYZE, VACUUM, REINDEX) via direct connection usage.
 */
class PostgresAdminServiceImplTest {

    private val repository = mockk<PostgresAdminRepository>()
    private val pgBouncerClient = mockk<PgBouncerClient>()
    private val pool = mockk<ConnectionPool>()
    private val connectionManager = mockk<ConnectionManager>()
    private val service = PostgresAdminServiceImpl(repository, pgBouncerClient)

    @OptIn(InternalDI::class)
    @BeforeTest
    fun setup() {
        mockkObject(ProviderRegistry)
        every { ProviderRegistry.get(ConnectionPool::class) } returns object : ObjectProvider<ConnectionPool> {
            override val type = ConnectionPool::class
            override suspend fun get() = pool
        }

        coEvery { pool.connection() } returns connectionManager
        coEvery { connectionManager.release() } just runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // region getDatabaseStats

    @Test
    fun `getDatabaseStats delegates to repository`() = runTest {
        val expected = PgDatabaseStats(
            databaseName = "bosca",
            numBackends = 10,
            xactCommit = 5000L,
            xactRollback = 100L,
            blksRead = 200L,
            blksHit = 9800L,
            tupReturned = 50000L,
            tupFetched = 40000L,
            tupInserted = 3000L,
            tupUpdated = 2000L,
            tupDeleted = 500L,
            conflicts = 0L,
            tempFiles = 5L,
            tempBytes = 10240L,
            deadlocks = 1L,
            cacheHitRatio = 98.0f,
            databaseSize = 1_073_741_824L,
            statsReset = "2026-01-01 00:00:00",
        )
        coEvery { repository.getDatabaseStats() } returns expected

        val stats = service.getDatabaseStats()
        assertEquals("bosca", stats.databaseName)
        assertEquals(10, stats.numBackends)
        assertEquals(98.0f, stats.cacheHitRatio)
        assertEquals(1_073_741_824L, stats.databaseSize)
        coVerify { repository.getDatabaseStats() }
    }

    @Test
    fun `getDatabaseStats throws when repository returns null`() = runTest {
        coEvery { repository.getDatabaseStats() } returns null

        try {
            service.getDatabaseStats()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Expected database stats"))
        }
    }

    // endregion

    // region getActiveQueries

    @Test
    fun `getActiveQueries without filter delegates to repository`() = runTest {
        val expected = listOf(
            PgActiveQuery(
                pid = 1, databaseName = "bosca", userName = "admin",
                applicationName = "app", clientAddr = "127.0.0.1", state = "active",
                query = "SELECT 1", queryStart = "2026-01-01", stateChange = null,
                xactStart = null, queryDurationSeconds = 1.5f, waitEventType = null,
                waitEvent = null, backendStart = "2026-01-01", backendType = "client backend",
            ),
            PgActiveQuery(
                pid = 2, databaseName = "bosca", userName = "admin",
                applicationName = "app", clientAddr = "127.0.0.1", state = "active",
                query = "SELECT 2", queryStart = "2026-01-01", stateChange = null,
                xactStart = null, queryDurationSeconds = 0.5f, waitEventType = null,
                waitEvent = null, backendStart = "2026-01-01", backendType = "client backend",
            ),
        )
        coEvery { repository.getActiveQueries() } returns expected

        val queries = service.getActiveQueries()
        assertEquals(2, queries.size)
        assertEquals(1, queries[0].pid)
        assertEquals("SELECT 1", queries[0].query)
        coVerify { repository.getActiveQueries() }
    }

    @Test
    fun `getActiveQueries with minDuration delegates to filtered method`() = runTest {
        val expected = listOf(
            PgActiveQuery(
                pid = 1, databaseName = "bosca", userName = "admin",
                applicationName = "app", clientAddr = "127.0.0.1", state = "active",
                query = "SELECT 1", queryStart = "2026-01-01", stateChange = null,
                xactStart = null, queryDurationSeconds = 10.0f, waitEventType = null,
                waitEvent = null, backendStart = "2026-01-01", backendType = "client backend",
            ),
        )
        coEvery { repository.getActiveQueriesMinDuration(5.0f) } returns expected

        val queries = service.getActiveQueries(minDurationSeconds = 5.0f)
        assertEquals(1, queries.size)
        coVerify { repository.getActiveQueriesMinDuration(5.0f) }
    }

    @Test
    fun `getActiveQueries returns empty list when no active queries`() = runTest {
        coEvery { repository.getActiveQueries() } returns emptyList()

        val queries = service.getActiveQueries()
        assertEquals(0, queries.size)
    }

    // endregion

    // region getSlowQueries

    @Test
    fun `getSlowQueries returns empty when extension not installed`() = runTest {
        coEvery { repository.checkExtensionInstalled("pg_stat_statements") } returns null

        val queries = service.getSlowQueries()
        assertEquals(0, queries.size)
    }

    @Test
    fun `getSlowQueries orders by total time by default`() = runTest {
        val expected = listOf(
            PgSlowQuery(
                query = "SELECT * FROM big_table", calls = 100L,
                totalTimeMs = 50000.0f, meanTimeMs = 500.0f, minTimeMs = 10.0f,
                maxTimeMs = 2000.0f, stddevTimeMs = 300.0f, rows = 10000L,
                sharedBlksHit = 5000L, sharedBlksRead = 500L, hitRatio = 90.9f,
            ),
        )
        coEvery { repository.checkExtensionInstalled("pg_stat_statements") } returns 1
        coEvery { repository.getSlowQueriesByTotal(20) } returns expected

        val queries = service.getSlowQueries()
        assertEquals(1, queries.size)
        assertEquals("SELECT * FROM big_table", queries[0].query)
        coVerify { repository.getSlowQueriesByTotal(20) }
    }

    @Test
    fun `getSlowQueries orders by mean time when specified`() = runTest {
        val expected = listOf(
            PgSlowQuery(
                query = "SELECT expensive()", calls = 5L,
                totalTimeMs = 2500.0f, meanTimeMs = 500.0f, minTimeMs = 400.0f,
                maxTimeMs = 600.0f, stddevTimeMs = 50.0f, rows = 5L,
                sharedBlksHit = 100L, sharedBlksRead = 50L, hitRatio = 66.7f,
            ),
        )
        coEvery { repository.checkExtensionInstalled("pg_stat_statements") } returns 1
        coEvery { repository.getSlowQueriesByMean(10) } returns expected

        val queries = service.getSlowQueries(limit = 10, orderBy = "mean")
        assertEquals(1, queries.size)
        coVerify { repository.getSlowQueriesByMean(10) }
    }

    // endregion

    // region getTableStats

    @Test
    fun `getTableStats without schema delegates to unfiltered method`() = runTest {
        val expected = listOf(
            PgTableStats(
                schemaName = "public", tableName = "users", seqScan = 100L,
                seqTupRead = 50000L, idxScan = 2000L, idxTupFetch = 1500L,
                nTupIns = 1000L, nTupUpd = 500L, nTupDel = 50L, nTupHotUpd = 200L,
                nLiveTup = 10000L, nDeadTup = 100L, lastVacuum = null,
                lastAutovacuum = "2026-01-01", lastAnalyze = null,
                lastAutoanalyze = "2026-01-01", vacuumCount = 0L,
                autovacuumCount = 5L, analyzeCount = 0L, autoanalyzeCount = 3L,
                totalSize = 50_000_000L, tableSize = 40_000_000L,
                indexSize = 10_000_000L, bloatRatio = 1.0f,
            ),
        )
        coEvery { repository.getTableStats() } returns expected

        val tables = service.getTableStats()
        assertEquals(1, tables.size)
        assertEquals("users", tables[0].tableName)
        coVerify { repository.getTableStats() }
    }

    @Test
    fun `getTableStats with schema delegates to filtered method`() = runTest {
        coEvery { repository.getTableStatsBySchema("public") } returns emptyList()

        val tables = service.getTableStats("public")
        assertEquals(0, tables.size)
        coVerify { repository.getTableStatsBySchema("public") }
    }

    // endregion

    // region getIndexStats

    @Test
    fun `getIndexStats without schema delegates to unfiltered method`() = runTest {
        val expected = listOf(
            PgIndexStats(
                schemaName = "public", tableName = "users", indexName = "users_pkey",
                idxScan = 5000L, idxTupRead = 4000L, idxTupFetch = 3500L,
                indexSize = 2_000_000L,
                indexDef = "CREATE INDEX users_pkey ON public.users USING btree (id)",
            ),
        )
        coEvery { repository.getIndexStats() } returns expected

        val indexes = service.getIndexStats()
        assertEquals(1, indexes.size)
        assertEquals("users_pkey", indexes[0].indexName)
        coVerify { repository.getIndexStats() }
    }

    @Test
    fun `getIndexStats with schema delegates to filtered method`() = runTest {
        coEvery { repository.getIndexStatsBySchema("public") } returns emptyList()

        val indexes = service.getIndexStats("public")
        assertEquals(0, indexes.size)
        coVerify { repository.getIndexStatsBySchema("public") }
    }

    // endregion

    // region getUnusedIndexes

    @Test
    fun `getUnusedIndexes delegates to repository`() = runTest {
        val expected = listOf(
            PgIndexStats(
                schemaName = "public", tableName = "users", indexName = "users_old_email_idx",
                idxScan = 0L, idxTupRead = 0L, idxTupFetch = 0L,
                indexSize = 5_000_000L,
                indexDef = "CREATE INDEX users_old_email_idx ON public.users (old_email)",
            ),
        )
        coEvery { repository.getUnusedIndexes(0L) } returns expected

        val unused = service.getUnusedIndexes()
        assertEquals(1, unused.size)
        assertEquals("users_old_email_idx", unused[0].indexName)
        assertEquals(0L, unused[0].idxScan)
        coVerify { repository.getUnusedIndexes(0L) }
    }

    // endregion

    // region getIndexSuggestions

    @Test
    fun `getIndexSuggestions delegates to repository`() = runTest {
        val expected = listOf(
            PgIndexSuggestion(
                schemaName = "public", tableName = "events", seqScan = 5000L,
                seqTupRead = 500000L, idxScan = 0L, tableSize = 100_000_000L,
                reason = "No index scans detected; 5000 sequential scans reading 500000 rows on a 95 MB table",
            ),
        )
        coEvery { repository.getIndexSuggestions(50L) } returns expected

        val suggestions = service.getIndexSuggestions(50)
        assertEquals(1, suggestions.size)
        assertEquals("events", suggestions[0].tableName)
        assertTrue(suggestions[0].reason.contains("No index scans detected"))
        coVerify { repository.getIndexSuggestions(50L) }
    }

    // endregion

    // region getLocks

    @Test
    fun `getLocks delegates to repository`() = runTest {
        val expected = listOf(
            PgLockInfo(
                pid = 42, lockType = "relation", databaseName = "bosca",
                relationName = "users", mode = "RowExclusiveLock", granted = true,
                query = "UPDATE users SET name = 'test'", state = "active",
                durationSeconds = 2.5f,
            ),
        )
        coEvery { repository.getLocks() } returns expected

        val locks = service.getLocks()
        assertEquals(1, locks.size)
        assertEquals(42, locks[0].pid)
        assertEquals("RowExclusiveLock", locks[0].mode)
        assertTrue(locks[0].granted)
        coVerify { repository.getLocks() }
    }

    // endregion

    // region getReplicationSlots

    @Test
    fun `getReplicationSlots delegates to repository`() = runTest {
        val expected = listOf(
            PgReplicationSlot(
                slotName = "my_slot", slotType = "logical", active = true,
                databaseName = "bosca", confirmedFlushLsn = "0/1234567",
                retainedWalBytes = 1_000_000L,
            ),
        )
        coEvery { repository.getReplicationSlots() } returns expected

        val slots = service.getReplicationSlots()
        assertEquals(1, slots.size)
        assertEquals("my_slot", slots[0].slotName)
        assertTrue(slots[0].active)
        coVerify { repository.getReplicationSlots() }
    }

    // endregion

    // region getConnectionPoolStats

    @Test
    fun `getConnectionPoolStats returns pool info from DI registry`() = runTest {
        @OptIn(InternalDI::class)
        every { ProviderRegistry.findAll(ConnectionPool::class) } returns listOf(
            object : ObjectProvider<ConnectionPool> {
                override val type = ConnectionPool::class
                override suspend fun get() = pool
            }
        )
        every { pool.name } returns "default"
        every { pool.maxConnections } returns 10
        every { pool.activeConnections } returns 3
        every { pool.createdConnections } returns 5
        every { pool.hasAvailableConnections } returns true

        val stats = service.getConnectionPoolStats()
        assertEquals(1, stats.size)
        assertEquals("default", stats[0].poolName)
        assertEquals(10, stats[0].maxConnections)
        assertEquals(3, stats[0].activeConnections)
    }

    // endregion

    // region getSettings

    @Test
    fun `getSettings maps setting with enum values`() = runTest {
        mockReadOnlyQuery(listOf(true)) { rs ->
            every { rs.getString("name") } returns "log_min_duration_statement"
            every { rs.getString("setting") } returns "1000"
            every { rs.getString("unit") } returns "ms"
            every { rs.getString("category") } returns "Statistics / Monitoring"
            every { rs.getString("short_desc") } returns "Sets minimum execution time"
            every { rs.getString("context") } returns "superuser"
            every { rs.getString("vartype") } returns "integer"
            every { rs.getString("source") } returns "configuration file"
            every { rs.getString("min_val") } returns "-1"
            every { rs.getString("max_val") } returns "2147483647"
            every { rs.getArray("enum_vals") } returns null
            every { rs.getBoolean("pending_restart") } returns false
        }

        val settings = service.getSettings("log_min")
        assertEquals(1, settings.size)
        assertEquals("log_min_duration_statement", settings[0].name)
        assertEquals("ms", settings[0].unit)
        assertNull(settings[0].enumVals)
    }

    // endregion

    // region getVacuumProgress

    @Test
    fun `getVacuumProgress delegates to repository`() = runTest {
        val expected = listOf(
            PgVacuumProgress(
                pid = 55, databaseName = "bosca", schemaName = "public",
                tableName = "events", phase = "scanning heap",
                heapBlksTotal = 10000L, heapBlksScanned = 5000L,
                heapBlksVacuumed = 4000L, numDeadTuples = 1500L,
            ),
        )
        coEvery { repository.getVacuumProgress() } returns expected

        val progress = service.getVacuumProgress()
        assertEquals(1, progress.size)
        assertEquals(55, progress[0].pid)
        assertEquals("scanning heap", progress[0].phase)
        coVerify { repository.getVacuumProgress() }
    }

    // endregion

    // region getTableIOStats

    @Test
    fun `getTableIOStats without schema delegates to unfiltered method`() = runTest {
        val expected = listOf(
            PgTableIOStats(
                schemaName = "public", tableName = "users",
                heapBlksRead = 100L, heapBlksHit = 9900L,
                idxBlksRead = 50L, idxBlksHit = 4950L,
                toastBlksRead = 0L, toastBlksHit = 0L,
                cacheHitRatio = 99.0f,
            ),
        )
        coEvery { repository.getTableIOStats() } returns expected

        val ioStats = service.getTableIOStats()
        assertEquals(1, ioStats.size)
        assertEquals("users", ioStats[0].tableName)
        assertEquals(99.0f, ioStats[0].cacheHitRatio)
        coVerify { repository.getTableIOStats() }
    }

    @Test
    fun `getTableIOStats with schema delegates to filtered method`() = runTest {
        coEvery { repository.getTableIOStatsBySchema("public") } returns emptyList()

        val ioStats = service.getTableIOStats("public")
        assertEquals(0, ioStats.size)
        coVerify { repository.getTableIOStatsBySchema("public") }
    }

    // endregion

    // region getBloatedTables

    @Test
    fun `getBloatedTables delegates to repository`() = runTest {
        val expected = listOf(
            PgTableStats(
                schemaName = "public", tableName = "events", seqScan = 100L,
                seqTupRead = 50000L, idxScan = 2000L, idxTupFetch = 1500L,
                nTupIns = 10000L, nTupUpd = 5000L, nTupDel = 3000L, nTupHotUpd = 200L,
                nLiveTup = 10000L, nDeadTup = 5000L, lastVacuum = null,
                lastAutovacuum = null, lastAnalyze = null, lastAutoanalyze = null,
                vacuumCount = 0L, autovacuumCount = 0L, analyzeCount = 0L,
                autoanalyzeCount = 0L, totalSize = 50_000_000L,
                tableSize = 40_000_000L, indexSize = 10_000_000L, bloatRatio = 50.0f,
            ),
        )
        coEvery { repository.getBloatedTables(10.0) } returns expected

        val tables = service.getBloatedTables(10.0)
        assertEquals(1, tables.size)
        assertEquals("events", tables[0].tableName)
        assertEquals(50.0f, tables[0].bloatRatio)
        coVerify { repository.getBloatedTables(10.0) }
    }

    // endregion

    // region getServerVersion

    @Test
    fun `getServerVersion delegates to repository`() = runTest {
        coEvery { repository.getServerVersion() } returns "PostgreSQL 17.2 on x86_64"

        val version = service.getServerVersion()
        assertEquals("PostgreSQL 17.2 on x86_64", version)
        coVerify { repository.getServerVersion() }
    }

    @Test
    fun `getServerVersion throws when repository returns null`() = runTest {
        coEvery { repository.getServerVersion() } returns null

        try {
            service.getServerVersion()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Expected server version"))
        }
    }

    // endregion

    // region getUptime

    @Test
    fun `getUptime delegates to repository`() = runTest {
        coEvery { repository.getUptime() } returns "5 days 03:22:11.123456"

        val uptime = service.getUptime()
        assertEquals("5 days 03:22:11.123456", uptime)
        coVerify { repository.getUptime() }
    }

    @Test
    fun `getUptime throws when repository returns null`() = runTest {
        coEvery { repository.getUptime() } returns null

        try {
            service.getUptime()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Expected uptime"))
        }
    }

    // endregion

    // region cancelQuery

    @Test
    fun `cancelQuery delegates to repository`() = runTest {
        coEvery { repository.cancelQuery(42) } returns true

        val result = service.cancelQuery(42)
        assertTrue(result)
        coVerify { repository.cancelQuery(42) }
    }

    @Test
    fun `cancelQuery returns false when repository returns null`() = runTest {
        coEvery { repository.cancelQuery(42) } returns null

        val result = service.cancelQuery(42)
        assertFalse(result)
    }

    // endregion

    // region terminateBackend

    @Test
    fun `terminateBackend delegates to repository`() = runTest {
        coEvery { repository.terminateBackend(42) } returns true

        val result = service.terminateBackend(42)
        assertTrue(result)
        coVerify { repository.terminateBackend(42) }
    }

    @Test
    fun `terminateBackend returns false when repository returns null`() = runTest {
        coEvery { repository.terminateBackend(42) } returns null

        val result = service.terminateBackend(42)
        assertFalse(result)
    }

    // endregion

    // region analyzeTable

    @Test
    fun `analyzeTable returns true on success`() = runTest {
        mockStatement(succeed = true)

        val result = service.analyzeTable("public", "users")
        assertTrue(result)
    }

    @Test
    fun `analyzeTable returns false on failure`() = runTest {
        mockStatement(succeed = false)

        val result = service.analyzeTable("public", "nonexistent")
        assertFalse(result)
    }

    // endregion

    // region resetStatStatements

    @Test
    fun `resetStatStatements returns false when extension not installed`() = runTest {
        coEvery { repository.checkExtensionInstalled("pg_stat_statements") } returns null

        val result = service.resetStatStatements()
        assertFalse(result)
    }

    @Test
    fun `resetStatStatements returns true when extension installed`() = runTest {
        coEvery { repository.checkExtensionInstalled("pg_stat_statements") } returns 1
        coEvery { repository.resetStatStatements() } returns ""

        val result = service.resetStatStatements()
        assertTrue(result)
        coVerify { repository.resetStatStatements() }
    }

    @Test
    fun `resetStatStatements returns false on exception`() = runTest {
        coEvery { repository.checkExtensionInstalled("pg_stat_statements") } returns 1
        coEvery { repository.resetStatStatements() } throws RuntimeException("Simulated failure")

        val result = service.resetStatStatements()
        assertFalse(result)
    }

    // endregion

    // region getWalStats

    @Test
    fun `getWalStats delegates to repository`() = runTest {
        val expected = PgWalStats(
            walRecords = 100000L, walFpi = 5000L, walBytes = 50_000_000L,
            walBuffersFull = 12L, walWrite = 800L, walSync = 600L,
            walWriteTime = 123.456, walSyncTime = 78.901,
            statsReset = "2026-01-15 00:00:00",
        )
        coEvery { repository.getWalStats() } returns expected

        val stats = service.getWalStats()
        assertEquals(100000L, stats.walRecords)
        assertEquals(5000L, stats.walFpi)
        assertEquals(50_000_000L, stats.walBytes)
        assertEquals(123.456, stats.walWriteTime)
        assertEquals("2026-01-15 00:00:00", stats.statsReset)
        coVerify { repository.getWalStats() }
    }

    @Test
    fun `getWalStats throws when repository returns null`() = runTest {
        coEvery { repository.getWalStats() } returns null

        try {
            service.getWalStats()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Expected WAL stats"))
        }
    }

    // endregion

    // region getCheckpointStats

    @Test
    fun `getCheckpointStats delegates to repository`() = runTest {
        val expected = PgCheckpointStats(
            checkpointsTimedCount = 150L, checkpointsRequestedCount = 8L,
            buffersCheckpoint = 60000L, buffersClean = 4000L,
            maxwrittenClean = 15L, buffersBackend = 3000L,
            buffersBackendFsync = 2L, buffersAlloc = 200000L,
            statsReset = "2026-02-01",
        )
        coEvery { repository.getCheckpointStats() } returns expected

        val stats = service.getCheckpointStats()
        assertEquals(150L, stats.checkpointsTimedCount)
        assertEquals(8L, stats.checkpointsRequestedCount)
        assertEquals(60000L, stats.buffersCheckpoint)
        assertEquals("2026-02-01", stats.statsReset)
        coVerify { repository.getCheckpointStats() }
    }

    @Test
    fun `getCheckpointStats throws when repository returns null`() = runTest {
        coEvery { repository.getCheckpointStats() } returns null

        try {
            service.getCheckpointStats()
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Expected checkpoint stats"))
        }
    }

    // endregion

    // region getBlockingChains

    @Test
    fun `getBlockingChains delegates to repository`() = runTest {
        val expected = listOf(
            PgBlockingChain(
                blockedPid = 100, blockedUser = "app_user",
                blockedQuery = "UPDATE accounts SET balance = 0",
                blockedState = "active", blockedDurationSeconds = 12.5f,
                blockingPid = 50, blockingUser = "admin",
                blockingQuery = "ALTER TABLE accounts ADD COLUMN foo text",
                blockingState = "active",
            ),
        )
        coEvery { repository.getBlockingChains() } returns expected

        val chains = service.getBlockingChains()
        assertEquals(1, chains.size)
        assertEquals(100, chains[0].blockedPid)
        assertEquals(50, chains[0].blockingPid)
        assertEquals(12.5f, chains[0].blockedDurationSeconds)
        coVerify { repository.getBlockingChains() }
    }

    @Test
    fun `getBlockingChains returns empty when no blocking`() = runTest {
        coEvery { repository.getBlockingChains() } returns emptyList()

        val chains = service.getBlockingChains()
        assertEquals(0, chains.size)
    }

    // endregion

    // region getLongRunningTransactions

    @Test
    fun `getLongRunningTransactions delegates to repository`() = runTest {
        val expected = listOf(
            PgLongTransaction(
                pid = 77, databaseName = "bosca", userName = "worker",
                applicationName = "batch-processor", state = "idle in transaction",
                xactStartTime = "2026-01-01 00:00:00", xactDurationSeconds = 300f,
                lastQuery = "SELECT * FROM big_table",
                waitEventType = "Client", waitEvent = "ClientRead",
            ),
        )
        coEvery { repository.getLongRunningTransactions(60f) } returns expected

        val txns = service.getLongRunningTransactions(60f)
        assertEquals(1, txns.size)
        assertEquals(77, txns[0].pid)
        assertEquals("idle in transaction", txns[0].state)
        assertEquals(300f, txns[0].xactDurationSeconds)
        coVerify { repository.getLongRunningTransactions(60f) }
    }

    @Test
    fun `getLongRunningTransactions returns empty when none found`() = runTest {
        coEvery { repository.getLongRunningTransactions(60f) } returns emptyList()

        val txns = service.getLongRunningTransactions(60f)
        assertEquals(0, txns.size)
    }

    // endregion

    // region getSequenceUsage

    @Test
    fun `getSequenceUsage delegates to repository`() = runTest {
        val expected = listOf(
            PgSequenceUsage(
                schemaName = "public", sequenceName = "users_id_seq",
                dataType = "integer", currentValue = 2_000_000_000L,
                maxValue = 2_147_483_647L, percentUsed = 93.13f,
            ),
        )
        coEvery { repository.getSequenceUsage(0f) } returns expected

        val sequences = service.getSequenceUsage(0f)
        assertEquals(1, sequences.size)
        assertEquals("users_id_seq", sequences[0].sequenceName)
        assertEquals(93.13f, sequences[0].percentUsed)
        coVerify { repository.getSequenceUsage(0f) }
    }

    @Test
    fun `getSequenceUsage returns empty when no sequences match threshold`() = runTest {
        coEvery { repository.getSequenceUsage(99f) } returns emptyList()

        val sequences = service.getSequenceUsage(99f)
        assertEquals(0, sequences.size)
    }

    // endregion

    // region getDatabaseSizeBreakdown

    @Test
    fun `getDatabaseSizeBreakdown delegates to repository`() = runTest {
        val expected = listOf(
            PgObjectSize(
                schemaName = "public", objectName = "events", objectType = "table",
                totalSize = 500_000_000L, tableSize = 400_000_000L, indexSize = 100_000_000L,
            ),
            PgObjectSize(
                schemaName = "public", objectName = "events_pkey", objectType = "index",
                totalSize = 100_000_000L, tableSize = null, indexSize = null,
            ),
        )
        coEvery { repository.getDatabaseSizeBreakdown(10) } returns expected

        val objects = service.getDatabaseSizeBreakdown(10)
        assertEquals(2, objects.size)
        assertEquals("events", objects[0].objectName)
        assertEquals("table", objects[0].objectType)
        assertEquals("events_pkey", objects[1].objectName)
        assertNull(objects[1].tableSize)
        coVerify { repository.getDatabaseSizeBreakdown(10) }
    }

    @Test
    fun `getDatabaseSizeBreakdown returns empty for empty database`() = runTest {
        coEvery { repository.getDatabaseSizeBreakdown(50) } returns emptyList()

        val objects = service.getDatabaseSizeBreakdown()
        assertEquals(0, objects.size)
    }

    // endregion

    // region getInstalledExtensions

    @Test
    fun `getInstalledExtensions delegates to repository`() = runTest {
        val expected = listOf(
            PgExtension(
                name = "plpgsql", installedVersion = "1.0",
                defaultVersion = "1.0", description = "PL/pgSQL procedural language",
            ),
            PgExtension(
                name = "pg_stat_statements", installedVersion = "1.10",
                defaultVersion = "1.10",
                description = "track planning and execution statistics of all SQL statements executed",
            ),
        )
        coEvery { repository.getInstalledExtensions() } returns expected

        val extensions = service.getInstalledExtensions()
        assertEquals(2, extensions.size)
        assertEquals("plpgsql", extensions[0].name)
        assertEquals("pg_stat_statements", extensions[1].name)
        coVerify { repository.getInstalledExtensions() }
    }

    @Test
    fun `getInstalledExtensions handles null default version`() = runTest {
        val expected = listOf(
            PgExtension(
                name = "custom_ext", installedVersion = "0.1",
                defaultVersion = null, description = null,
            ),
        )
        coEvery { repository.getInstalledExtensions() } returns expected

        val extensions = service.getInstalledExtensions()
        assertEquals(1, extensions.size)
        assertNull(extensions[0].defaultVersion)
        assertNull(extensions[0].description)
    }

    // endregion

    // region vacuumTable

    @Test
    fun `vacuumTable returns true on success`() = runTest {
        mockStatement(succeed = true)

        val result = service.vacuumTable("public", "users")
        assertTrue(result)
    }

    @Test
    fun `vacuumTable with full=true returns true on success`() = runTest {
        mockStatement(succeed = true)

        val result = service.vacuumTable("public", "users", full = true)
        assertTrue(result)
    }

    @Test
    fun `vacuumTable returns false on failure`() = runTest {
        mockStatement(succeed = false)

        val result = service.vacuumTable("public", "nonexistent")
        assertFalse(result)
    }

    // endregion

    // region reindex

    @Test
    fun `reindex returns true on success`() = runTest {
        mockStatement(succeed = true)

        val result = service.reindex("public", "users_email_idx")
        assertTrue(result)
    }

    @Test
    fun `reindex returns false on failure`() = runTest {
        mockStatement(succeed = false)

        val result = service.reindex("public", "nonexistent_idx")
        assertFalse(result)
    }

    // endregion

    // region getPgBouncerInfo

    @Test
    fun `getPgBouncerInfo returns unavailable when version is null`() = runTest {
        coEvery { pgBouncerClient.getVersion() } returns null

        val info = service.getPgBouncerInfo()
        assertFalse(info.available)
        assertNull(info.version)
        assertEquals(0, info.pools.size)
    }

    @Test
    fun `getPgBouncerInfo returns available with data when version succeeds`() = runTest {
        coEvery { pgBouncerClient.getVersion() } returns "PgBouncer 1.22.0"
        coEvery { pgBouncerClient.getPools() } returns emptyList()
        coEvery { pgBouncerClient.getStats() } returns emptyList()
        coEvery { pgBouncerClient.getDatabases() } returns emptyList()

        val info = service.getPgBouncerInfo()
        assertTrue(info.available)
        assertEquals("PgBouncer 1.22.0", info.version)
    }

    // endregion

    // region getReplicationStatus

    @Test
    fun `getReplicationStatus delegates to repository`() = runTest {
        val expected = listOf(
            PgReplicationStatus(
                pid = 1234, userName = "replicator",
                applicationName = "replica-1", clientAddr = "10.0.0.2",
                state = "streaming", sentLsn = "0/1000000",
                writeLsn = "0/1000000", flushLsn = "0/1000000",
                replayLsn = "0/F00000", replayLagSeconds = 0.001f,
                writeLagSeconds = 0.0f, flushLagSeconds = 0.0f,
                syncState = "async", syncPriority = 0,
            ),
        )
        coEvery { repository.getReplicationStatus() } returns expected

        val status = service.getReplicationStatus()
        assertEquals(1, status.size)
        assertEquals("replica-1", status[0].applicationName)
        coVerify { repository.getReplicationStatus() }
    }

    // endregion

    // region isInRecovery

    @Test
    fun `isInRecovery delegates to repository`() = runTest {
        coEvery { repository.isInRecovery() } returns false

        val result = service.isInRecovery()
        assertFalse(result)
        coVerify { repository.isInRecovery() }
    }

    @Test
    fun `isInRecovery returns false when repository returns null`() = runTest {
        coEvery { repository.isInRecovery() } returns null

        val result = service.isInRecovery()
        assertFalse(result)
    }

    // endregion

    // region Helpers

    /**
     * Helper to set up the mock [ConnectionManager] so that when [ConnectionManager.useReadOnlyStatement]
     * is called with any SQL, it creates a mock [ResultSet] populated by the given [setup] function.
     * Used only for the settings query which still uses manual ResultSet mapping.
     */
    private fun mockReadOnlyQuery(hasNext: List<Boolean>, setup: (ResultSet) -> Unit) {
        coEvery { connectionManager.useReadOnlyStatement<Any?>(any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[1] as suspend (PreparedStatement) -> Any?
            val stmt = mockk<PreparedStatement>(relaxed = true)
            val rs = mockk<ResultSet>()
            val nextIterator = hasNext.iterator()
            every { rs.next() } answers { nextIterator.hasNext() && nextIterator.next() }
            every { rs.close() } returns Unit
            setup(rs)
            every { stmt.executeQuery() } returns rs
            block(stmt)
        }
    }

    /**
     * Helper to set up the mock [ConnectionManager] so that when [ConnectionManager.useStatement]
     * is called for DDL operations (ANALYZE, VACUUM, REINDEX), it simulates success or failure.
     */
    private fun mockStatement(succeed: Boolean = true) {
        coEvery { connectionManager.useStatement<Any?>(any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[1] as suspend (PreparedStatement) -> Any?
            if (!succeed) throw java.sql.SQLException("Simulated failure")
            val stmt = mockk<PreparedStatement>()
            every { stmt.execute() } returns true
            block(stmt)
        }
    }

    // endregion
}
