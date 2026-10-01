package bosca.postgres.admin.graphql

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
import bosca.postgres.admin.service.PostgresAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that [PostgresAdminController] correctly delegates to [PostgresAdminService]
 * after admin authorization, and rejects unauthorized access for all query endpoints.
 */
class PostgresAdminControllerTest {

    private val service = mockk<PostgresAdminService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = PostgresAdminController(service, groupEvaluator)
    private val auth = mockk<AuthenticationContext>()

    // region databaseStats

    @Test
    fun `databaseStats delegates to service after admin check`() = runTest {
        val stats = PgDatabaseStats(
            databaseName = "bosca",
            numBackends = 5,
            xactCommit = 100,
            xactRollback = 2,
            blksRead = 50,
            blksHit = 950,
            tupReturned = 1000,
            tupFetched = 800,
            tupInserted = 200,
            tupUpdated = 100,
            tupDeleted = 10,
            conflicts = 0,
            tempFiles = 0,
            tempBytes = 0,
            deadlocks = 0,
            cacheHitRatio = 95.0f,
            databaseSize = 1_000_000,
            statsReset = null,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getDatabaseStats() } returns stats

        val result = controller.databaseStats(auth)
        assertEquals("bosca", result.databaseName)
        assertEquals(95.0f, result.cacheHitRatio)
        coVerify { service.getDatabaseStats() }
    }

    @Test
    fun `databaseStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.databaseStats(auth) }
    }

    // endregion

    // region activeQueries

    @Test
    fun `activeQueries rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.activeQueries(auth, null) }
    }

    @Test
    fun `activeQueries passes minDurationSeconds to service`() = runTest {
        val query = PgActiveQuery(
            pid = 42,
            databaseName = "bosca",
            userName = "admin",
            applicationName = "app",
            clientAddr = "127.0.0.1",
            state = "active",
            query = "SELECT 1",
            queryStart = "2026-01-01",
            stateChange = null,
            xactStart = null,
            queryDurationSeconds = 5.0f,
            waitEventType = null,
            waitEvent = null,
            backendStart = null,
            backendType = "client backend",
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getActiveQueries(5.0f) } returns listOf(query)

        val result = controller.activeQueries(auth, 5.0f)
        assertEquals(1, result.size)
        assertEquals(42, result[0].pid)
        coVerify { service.getActiveQueries(5.0f) }
    }

    @Test
    fun `activeQueries passes null duration`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getActiveQueries(null) } returns emptyList()

        val result = controller.activeQueries(auth, null)
        assertEquals(0, result.size)
    }

    // endregion

    // region slowQueries

    @Test
    fun `slowQueries rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.slowQueries(auth, null, null) }
    }

    @Test
    fun `slowQueries uses defaults when args are null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getSlowQueries(20, "total") } returns emptyList()

        controller.slowQueries(auth, null, null)
        coVerify { service.getSlowQueries(20, "total") }
    }

    @Test
    fun `slowQueries passes explicit limit and orderBy`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getSlowQueries(5, "mean") } returns emptyList()

        controller.slowQueries(auth, 5, "mean")
        coVerify { service.getSlowQueries(5, "mean") }
    }

    // endregion

    // region tableStats

    @Test
    fun `tableStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.tableStats(auth, null) }
    }

    @Test
    fun `tableStats passes schema filter`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getTableStats("public") } returns emptyList()

        controller.tableStats(auth, "public")
        coVerify { service.getTableStats("public") }
    }

    // endregion

    // region indexStats

    @Test
    fun `indexStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.indexStats(auth, null) }
    }

    @Test
    fun `indexStats delegates with schema`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getIndexStats(null) } returns emptyList()

        controller.indexStats(auth, null)
        coVerify { service.getIndexStats(null) }
    }

    // endregion

    // region unusedIndexes

    @Test
    fun `unusedIndexes rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.unusedIndexes(auth, null) }
    }

    @Test
    fun `unusedIndexes uses default minTableSize when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getUnusedIndexes(0) } returns emptyList()

        controller.unusedIndexes(auth, null)
        coVerify { service.getUnusedIndexes(0) }
    }

    @Test
    fun `unusedIndexes passes explicit minTableSize`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getUnusedIndexes(1024) } returns emptyList()

        controller.unusedIndexes(auth, 1024)
        coVerify { service.getUnusedIndexes(1024) }
    }

    // endregion

    // region indexSuggestions

    @Test
    fun `indexSuggestions rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.indexSuggestions(auth, null) }
    }

    @Test
    fun `indexSuggestions uses default minSeqScans when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getIndexSuggestions(50) } returns emptyList()

        controller.indexSuggestions(auth, null)
        coVerify { service.getIndexSuggestions(50) }
    }

    // endregion

    // region locks

    @Test
    fun `locks rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.locks(auth) }
    }

    @Test
    fun `locks delegates to service`() = runTest {
        val lock = PgLockInfo(
            pid = 10,
            lockType = "relation",
            databaseName = "bosca",
            relationName = "users",
            mode = "AccessExclusiveLock",
            granted = true,
            query = "ALTER TABLE users",
            state = "active",
            durationSeconds = 3.5f,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getLocks() } returns listOf(lock)

        val result = controller.locks(auth)
        assertEquals(1, result.size)
        assertEquals("AccessExclusiveLock", result[0].mode)
    }

    // endregion

    // region replicationSlots

    @Test
    fun `replicationSlots rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.replicationSlots(auth) }
    }

    @Test
    fun `replicationSlots delegates to service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getReplicationSlots() } returns emptyList()

        val result = controller.replicationSlots(auth)
        assertEquals(0, result.size)
    }

    // endregion

    // region connectionPoolStats

    @Test
    fun `connectionPoolStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.connectionPoolStats(auth) }
    }

    @Test
    fun `connectionPoolStats delegates to service`() = runTest {
        val stats = PgConnectionPoolStats(
            poolName = "default",
            maxConnections = 10,
            activeConnections = 3,
            createdConnections = 5,
            hasAvailableConnections = true,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getConnectionPoolStats() } returns listOf(stats)

        val result = controller.connectionPoolStats(auth)
        assertEquals(1, result.size)
        assertEquals("default", result[0].poolName)
    }

    // endregion

    // region settings

    @Test
    fun `settings rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.settings(auth, null) }
    }

    @Test
    fun `settings passes filter`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getSettings("autovacuum") } returns emptyList()

        controller.settings(auth, "autovacuum")
        coVerify { service.getSettings("autovacuum") }
    }

    // endregion

    // region vacuumProgress

    @Test
    fun `vacuumProgress rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.vacuumProgress(auth) }
    }

    @Test
    fun `vacuumProgress delegates to service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getVacuumProgress() } returns emptyList()

        val result = controller.vacuumProgress(auth)
        assertEquals(0, result.size)
    }

    // endregion

    // region tableIOStats

    @Test
    fun `tableIOStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.tableIOStats(auth, null) }
    }

    @Test
    fun `tableIOStats passes schema filter`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getTableIOStats("public") } returns emptyList()

        controller.tableIOStats(auth, "public")
        coVerify { service.getTableIOStats("public") }
    }

    // endregion

    // region bloatedTables

    @Test
    fun `bloatedTables rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.bloatedTables(auth, null) }
    }

    @Test
    fun `bloatedTables uses default minBloatPercent when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getBloatedTables(10.0) } returns emptyList()

        controller.bloatedTables(auth, null)
        coVerify { service.getBloatedTables(10.0) }
    }

    @Test
    fun `bloatedTables passes explicit threshold`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getBloatedTables(25.0) } returns emptyList()

        controller.bloatedTables(auth, 25.0)
        coVerify { service.getBloatedTables(25.0) }
    }

    // endregion

    // region serverVersion

    @Test
    fun `serverVersion rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.serverVersion(auth) }
    }

    @Test
    fun `serverVersion delegates to service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getServerVersion() } returns "PostgreSQL 17.2"

        val result = controller.serverVersion(auth)
        assertEquals("PostgreSQL 17.2", result)
    }

    // endregion

    // region uptime

    @Test
    fun `uptime rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.uptime(auth) }
    }

    @Test
    fun `uptime delegates to service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getUptime() } returns "3 days 04:30:15"

        val result = controller.uptime(auth)
        assertEquals("3 days 04:30:15", result)
    }

    // endregion

    // region walStats

    @Test
    fun `walStats delegates to service after admin check`() = runTest {
        val stats = PgWalStats(
            walRecords = 10000,
            walFpi = 500,
            walBytes = 5_000_000,
            walBuffersFull = 3,
            walWrite = 200,
            walSync = 150,
            walWriteTime = 123.45,
            walSyncTime = 67.89,
            statsReset = null,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getWalStats() } returns stats

        val result = controller.walStats(auth)
        assertEquals(10000, result.walRecords)
        assertEquals(5_000_000, result.walBytes)
    }

    @Test
    fun `walStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.walStats(auth) }
    }

    // endregion

    // region checkpointStats

    @Test
    fun `checkpointStats delegates to service`() = runTest {
        val stats = PgCheckpointStats(
            checkpointsTimedCount = 100,
            checkpointsRequestedCount = 5,
            buffersCheckpoint = 50000,
            buffersClean = 3000,
            maxwrittenClean = 10,
            buffersBackend = 2000,
            buffersBackendFsync = 0,
            buffersAlloc = 100000,
            statsReset = "2026-01-01",
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getCheckpointStats() } returns stats

        val result = controller.checkpointStats(auth)
        assertEquals(100, result.checkpointsTimedCount)
        assertEquals(5, result.checkpointsRequestedCount)
        assertEquals("2026-01-01", result.statsReset)
    }

    @Test
    fun `checkpointStats rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.checkpointStats(auth) }
    }

    // endregion

    // region blockingChains

    @Test
    fun `blockingChains delegates to service`() = runTest {
        val chain = PgBlockingChain(
            blockedPid = 100,
            blockedUser = "app_user",
            blockedQuery = "UPDATE accounts SET balance = 0",
            blockedState = "active",
            blockedDurationSeconds = 12.5f,
            blockingPid = 50,
            blockingUser = "admin",
            blockingQuery = "ALTER TABLE accounts ADD COLUMN foo text",
            blockingState = "active",
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getBlockingChains() } returns listOf(chain)

        val result = controller.blockingChains(auth)
        assertEquals(1, result.size)
        assertEquals(100, result[0].blockedPid)
        assertEquals(50, result[0].blockingPid)
    }

    @Test
    fun `blockingChains rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.blockingChains(auth) }
    }

    // endregion

    // region longRunningTransactions

    @Test
    fun `longRunningTransactions uses default minDuration when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getLongRunningTransactions(60f) } returns emptyList()

        controller.longRunningTransactions(auth, null)
        coVerify { service.getLongRunningTransactions(60f) }
    }

    @Test
    fun `longRunningTransactions passes explicit duration`() = runTest {
        val txn = PgLongTransaction(
            pid = 77,
            databaseName = "bosca",
            userName = "app",
            applicationName = "worker",
            state = "idle in transaction",
            xactStartTime = "2026-01-01 00:00:00",
            xactDurationSeconds = 300f,
            lastQuery = "SELECT * FROM big_table",
            waitEventType = "Client",
            waitEvent = "ClientRead",
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getLongRunningTransactions(120f) } returns listOf(txn)

        val result = controller.longRunningTransactions(auth, 120f)
        assertEquals(1, result.size)
        assertEquals("idle in transaction", result[0].state)
        assertEquals(300f, result[0].xactDurationSeconds)
    }

    @Test
    fun `longRunningTransactions rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.longRunningTransactions(auth, null) }
    }

    // endregion

    // region sequenceUsage

    @Test
    fun `sequenceUsage uses default minPercentUsed when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getSequenceUsage(0f) } returns emptyList()

        controller.sequenceUsage(auth, null)
        coVerify { service.getSequenceUsage(0f) }
    }

    @Test
    fun `sequenceUsage passes explicit threshold`() = runTest {
        val seq = PgSequenceUsage(
            schemaName = "public",
            sequenceName = "users_id_seq",
            dataType = "integer",
            currentValue = 2_000_000_000,
            maxValue = 2_147_483_647,
            percentUsed = 93.12f,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getSequenceUsage(75f) } returns listOf(seq)

        val result = controller.sequenceUsage(auth, 75f)
        assertEquals(1, result.size)
        assertEquals("integer", result[0].dataType)
        assertEquals(93.12f, result[0].percentUsed)
    }

    @Test
    fun `sequenceUsage rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.sequenceUsage(auth, null) }
    }

    // endregion

    // region databaseSizeBreakdown

    @Test
    fun `databaseSizeBreakdown uses default limit when null`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getDatabaseSizeBreakdown(50) } returns emptyList()

        controller.databaseSizeBreakdown(auth, null)
        coVerify { service.getDatabaseSizeBreakdown(50) }
    }

    @Test
    fun `databaseSizeBreakdown passes explicit limit`() = runTest {
        val obj = PgObjectSize(
            schemaName = "public",
            objectName = "events",
            objectType = "table",
            totalSize = 500_000_000,
            tableSize = 400_000_000,
            indexSize = 100_000_000,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getDatabaseSizeBreakdown(10) } returns listOf(obj)

        val result = controller.databaseSizeBreakdown(auth, 10)
        assertEquals(1, result.size)
        assertEquals("events", result[0].objectName)
        assertEquals(500_000_000, result[0].totalSize)
    }

    @Test
    fun `databaseSizeBreakdown rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.databaseSizeBreakdown(auth, null) }
    }

    // endregion

    // region installedExtensions

    @Test
    fun `installedExtensions delegates to service`() = runTest {
        val ext = PgExtension(
            name = "pg_stat_statements",
            installedVersion = "1.10",
            defaultVersion = "1.10",
            description = "track planning and execution statistics",
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getInstalledExtensions() } returns listOf(ext)

        val result = controller.installedExtensions(auth)
        assertEquals(1, result.size)
        assertEquals("pg_stat_statements", result[0].name)
        assertEquals("1.10", result[0].installedVersion)
    }

    @Test
    fun `installedExtensions rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.installedExtensions(auth) }
    }

    // endregion

    // region pgBouncerInfo

    @Test
    fun `pgBouncerInfo delegates to service`() = runTest {
        val info = PgBouncerInfo(
            available = false,
            version = null,
            pools = emptyList(),
            stats = emptyList(),
            databases = emptyList(),
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getPgBouncerInfo() } returns info

        val result = controller.pgBouncerInfo(auth)
        assertEquals(false, result.available)
        coVerify { service.getPgBouncerInfo() }
    }

    @Test
    fun `pgBouncerInfo rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.pgBouncerInfo(auth) }
    }

    // endregion

    // region replicationStatus

    @Test
    fun `replicationStatus delegates to service`() = runTest {
        val status = PgReplicationStatus(
            pid = 1234,
            userName = "replicator",
            applicationName = "replica-1",
            clientAddr = "10.0.0.2",
            state = "streaming",
            sentLsn = "0/1000000",
            writeLsn = "0/1000000",
            flushLsn = "0/1000000",
            replayLsn = "0/F00000",
            replayLagSeconds = 0.001f,
            writeLagSeconds = 0.0f,
            flushLagSeconds = 0.0f,
            syncState = "async",
            syncPriority = 0,
        )
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.getReplicationStatus() } returns listOf(status)

        val result = controller.replicationStatus(auth)
        assertEquals(1, result.size)
        assertEquals("replica-1", result[0].applicationName)
        assertEquals("streaming", result[0].state)
    }

    @Test
    fun `replicationStatus rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.replicationStatus(auth) }
    }

    // endregion

    // region isInRecovery

    @Test
    fun `isInRecovery delegates to service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } returns Unit
        coEvery { service.isInRecovery() } returns false

        val result = controller.isInRecovery(auth)
        assertEquals(false, result)
        coVerify { service.isInRecovery() }
    }

    @Test
    fun `isInRecovery rejects non-admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("Unauthorized access")
        assertFailsWith<SecurityException> { controller.isInRecovery(auth) }
    }

    // endregion
}
