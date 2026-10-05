package bosca.postgres.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies data class equality, copy, and nullable field behavior for all
 * postgres-admin model types.
 */
class ModelTest {

    @Test
    fun `PgWalStats supports equality and copy`() {
        val stats = PgWalStats(
            walRecords = 100,
            walFpi = 50,
            walBytes = 5000,
            walBuffersFull = 3,
            walWrite = 200,
            walSync = 150,
            walWriteTime = 1.5,
            walSyncTime = 0.8,
            statsReset = null,
        )
        val copy = stats.copy(walRecords = 200)
        assertEquals(200, copy.walRecords)
        assertNull(copy.statsReset)
        assertNotEquals(stats, copy)
    }

    @Test
    fun `PgCheckpointStats supports equality and copy`() {
        val stats = PgCheckpointStats(
            checkpointsTimedCount = 100,
            checkpointsRequestedCount = 5,
            buffersCheckpoint = 60000,
            buffersClean = 4000,
            maxwrittenClean = 15,
            buffersBackend = 3000,
            buffersBackendFsync = 0,
            buffersAlloc = 200000,
            statsReset = "2026-01-01",
        )
        val copy = stats.copy(checkpointsTimedCount = 200)
        assertEquals(200, copy.checkpointsTimedCount)
        assertEquals("2026-01-01", copy.statsReset)
    }

    @Test
    fun `PgBlockingChain nullable fields default correctly`() {
        val chain = PgBlockingChain(
            blockedPid = 100,
            blockedUser = null,
            blockedQuery = null,
            blockedState = null,
            blockedDurationSeconds = null,
            blockingPid = 50,
            blockingUser = null,
            blockingQuery = null,
            blockingState = null,
        )
        assertEquals(100, chain.blockedPid)
        assertEquals(50, chain.blockingPid)
        assertNull(chain.blockedUser)
        assertNull(chain.blockedDurationSeconds)
    }

    @Test
    fun `PgLongTransaction represents idle-in-transaction session`() {
        val txn = PgLongTransaction(
            pid = 42,
            databaseName = "bosca",
            userName = "app",
            applicationName = "worker",
            state = "idle in transaction",
            xactStartTime = "2026-01-01 00:00:00",
            xactDurationSeconds = 600f,
            lastQuery = "SELECT 1",
            waitEventType = "Client",
            waitEvent = "ClientRead",
        )
        assertEquals("idle in transaction", txn.state)
        assertEquals(600f, txn.xactDurationSeconds)
    }

    @Test
    fun `PgSequenceUsage tracks exhaustion risk`() {
        val seq = PgSequenceUsage(
            schemaName = "public",
            sequenceName = "users_id_seq",
            dataType = "integer",
            currentValue = 2_000_000_000,
            maxValue = 2_147_483_647,
            percentUsed = 93.13f,
        )
        assertTrue(seq.percentUsed > 90f)
        assertEquals("integer", seq.dataType)
    }

    @Test
    fun `PgObjectSize distinguishes tables from indexes`() {
        val table = PgObjectSize(
            schemaName = "public",
            objectName = "events",
            objectType = "table",
            totalSize = 500_000_000,
            tableSize = 400_000_000,
            indexSize = 100_000_000,
        )
        val index = PgObjectSize(
            schemaName = "public",
            objectName = "events_pkey",
            objectType = "index",
            totalSize = 100_000_000,
            tableSize = null,
            indexSize = null,
        )
        assertEquals("table", table.objectType)
        assertEquals("index", index.objectType)
        assertNull(index.tableSize)
    }

    @Test
    fun `PgExtension tracks version info`() {
        val ext = PgExtension(
            name = "pg_stat_statements",
            installedVersion = "1.10",
            defaultVersion = "1.11",
            description = "track planning and execution statistics",
        )
        assertEquals("pg_stat_statements", ext.name)
        assertNotEquals(ext.installedVersion, ext.defaultVersion)
    }

    @Test
    fun `PgExtension handles null optional fields`() {
        val ext = PgExtension(
            name = "custom",
            installedVersion = "0.1",
            defaultVersion = null,
            description = null,
        )
        assertNull(ext.defaultVersion)
        assertNull(ext.description)
    }

    @Test
    fun `PgDatabaseStats equality works`() {
        val a = PgDatabaseStats("db", 1, 1, 0, 1, 99, 100, 80, 10, 5, 2, 0, 0, 0, 0, 99.0f, 1000, null)
        val b = PgDatabaseStats("db", 1, 1, 0, 1, 99, 100, 80, 10, 5, 2, 0, 0, 0, 0, 99.0f, 1000, null)
        assertEquals(a, b)
    }

    @Test
    fun `PgActiveQuery nullable duration handled`() {
        val q = PgActiveQuery(
            pid = 1,
            databaseName = null,
            userName = null,
            applicationName = null,
            clientAddr = null,
            state = null,
            query = null,
            queryStart = null,
            stateChange = null,
            xactStart = null,
            queryDurationSeconds = null,
            waitEventType = null,
            waitEvent = null,
            backendStart = null,
            backendType = null,
        )
        assertNull(q.queryDurationSeconds)
        assertNull(q.databaseName)
    }

    @Test
    fun `PgLockInfo distinguishes granted from waiting`() {
        val granted = PgLockInfo(1, "relation", "db", "t", "RowExclusiveLock", true, "q", "active", 1.0f)
        val waiting = PgLockInfo(2, "relation", "db", "t", "AccessExclusiveLock", false, "q", "active", 5.0f)
        assertTrue(granted.granted)
        assertTrue(!waiting.granted)
    }

    @Test
    fun `PgTableStats bloat ratio calculation`() {
        val stats = PgTableStats(
            "public", "t", 0, 0, 0, 0, 0, 0, 0, 0,
            10000, 5000, null, null, null, null,
            0, 0, 0, 0, 100, 80, 20, 50.0f
        )
        assertEquals(50.0f, stats.bloatRatio)
    }

    @Test
    fun `PgConnectionPoolStats reports availability`() {
        val stats = PgConnectionPoolStats("pool", 10, 3, 5, true)
        assertTrue(stats.hasAvailableConnections)
        assertEquals(7, stats.maxConnections - stats.activeConnections)
    }

    @Test
    fun `PgReplicationSlot with null retained WAL`() {
        val slot = PgReplicationSlot("slot1", "physical", false, null, null, null)
        assertNull(slot.retainedWalBytes)
        assertNull(slot.databaseName)
    }

    @Test
    fun `PgSlowQuery tracks execution metrics`() {
        val q = PgSlowQuery("SELECT 1", 1000, 500f, 0.5f, 0.1f, 2.0f, 0.3f, 1000, 950, 50, 95.0f)
        assertEquals(1000, q.calls)
        assertEquals(0.5f, q.meanTimeMs)
    }

    @Test
    fun `PgSetting with enum values`() {
        val s = PgSetting(
            "log_level", "warning", null, "Logging", "Sets log level",
            "superuser", "enum", "configuration file", null, null,
            listOf("debug", "info", "warning", "error"), false
        )
        assertEquals(4, s.enumVals?.size)
        assertTrue(s.enumVals!!.contains("warning"))
    }

    @Test
    fun `PgVacuumProgress tracks phase`() {
        val p = PgVacuumProgress(1, "db", "public", "events", "scanning heap", 10000, 5000, 4000, 1500)
        assertEquals("scanning heap", p.phase)
        assertEquals(10000L, p.heapBlksTotal)
    }

    @Test
    fun `PgTableIOStats cache hit ratio`() {
        val io = PgTableIOStats("public", "users", 100, 9900, 50, 4950, 0, 0, 99.0f)
        assertEquals(99.0f, io.cacheHitRatio)
    }

    @Test
    fun `PgIndexSuggestion captures reason`() {
        val s = PgIndexSuggestion("public", "events", 5000, 500000, 0, 100_000_000, "No index scans detected")
        assertTrue(s.reason.contains("No index scans"))
        assertEquals(0, s.idxScan)
    }
}
