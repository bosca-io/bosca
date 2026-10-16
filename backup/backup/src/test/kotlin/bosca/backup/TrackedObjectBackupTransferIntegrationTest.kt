package bosca.backup

import bosca.backup.ObjectBackupOperation.BACKUP
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises transfer/progress coordination with the registered repository and real stored bytes. */
@OptIn(InternalDI::class)
class TrackedObjectBackupTransferIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var progressService: ObjectBackupProgressService
    private lateinit var directory: Path
    private lateinit var source: FileSystemObjectStorageService
    private lateinit var destination: FileSystemObjectStorageService
    private val data = "data".toByteArray()
    private val entry = BackupObject(
        store = "content",
        key = "metadata/item/1/content",
        revision = "revision-1",
        length = 4,
        sha256 = "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7",
        payloadPath = "objects/revision-1",
    )
    private val sourcePath = StringObjectPath(entry.key)
    private val destinationPath = StringObjectPath(entry.payloadPath)

    @BeforeTest
    fun setUp() = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("tracked_object_transfer").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 1),
            key = "tracked-object-transfer-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        progressService = provide()

        directory = createTempDirectory("bosca-tracked-transfer-")
        source = storage("source")
        destination = storage("destination")
    }

    @AfterTest
    fun tearDown() = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `a copy completes durable progress only after persisted bytes are verified`() = runBlocking {
        writeSource()
        val target = mockk<ObjectStorageService>()
        coEvery { target.getInputStream(any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            assertEquals(ObjectBackupProgressStatus.RUNNING, persisted().status)
            assertEquals(0L, persisted().verifiedBytes)
            destination.getInputStream(firstArg())
        }
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            assertEquals(ObjectBackupProgressStatus.RUNNING, persisted().status)
            destination.setInputStream(firstArg(), secondArg(), thirdArg())
        }
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            assertEquals(ObjectBackupProgressStatus.RUNNING, persisted().status)
            source.getInputStream(firstArg())
        }

        val result = copy(origin, target)

        assertEquals(result, persisted())
        assertEquals(ObjectBackupProgressStatus.COMPLETED, result.status)
        assertEquals(1, result.attempts)
        assertEquals(entry.length, result.verifiedBytes)
        assertNull(result.error)
        assertEquals("data", destination.getString(destinationPath))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `completed replay verifies output without opening source or rewriting bytes`() = runBlocking {
        writeSource()
        val completed = copy()
        val origin = mockk<ObjectStorageService>()
        val target = readOnlyDestination()

        val replayed = copy(origin, target)

        assertEquals(completed, replayed)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `stopped running attempt reconciles output after a lost acknowledgement`() = runBlocking {
        val prepared = withConnectionManager { prepare() }
        withConnectionManager { progressService.start(prepared.key, prepared.version) }
        destination.setInputStream(destinationPath, data.inputStream(), entry.length)
        val origin = mockk<ObjectStorageService>()
        val target = readOnlyDestination()

        val reconciled = copy(origin, target)

        assertEquals(prepared.key, reconciled.key)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, reconciled.status)
        assertEquals(2, reconciled.attempts)
        assertEquals(entry.length, reconciled.verifiedBytes)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `partial write failure survives restart and retries the same intent`() = runBlocking {
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } answers {
            object : ByteArrayInputStream(data) {
                private var reads = 0

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw IOException("source disconnected")
                    return super.read(buffer, offset, minOf(length, 2))
                }
            }
        }

        assertFailsWith<IOException> { copy(origin) }

        val failed = persisted()
        assertEquals(ObjectBackupProgressStatus.FAILED, failed.status)
        assertEquals(1, failed.attempts)
        assertEquals(0L, failed.verifiedBytes)
        assertEquals("source disconnected", failed.error)
        assertEquals("da", destination.getString(destinationPath))

        writeSource()
        progressService = ObjectBackupProgressServiceImpl(provide())
        val retried = copy()

        assertEquals(failed.key, retried.key)
        assertEquals(failed.created, retried.created)
        assertEquals(2, retried.attempts)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, retried.status)
        assertEquals("data", destination.getString(destinationPath))
    }

    @Test
    fun `missing completed destination is invalidated and repaired`() = runBlocking {
        writeSource()
        val completed = copy()
        destination.delete(destinationPath)

        val repaired = copy()

        assertEquals(completed.key, repaired.key)
        assertTrue(repaired.version > completed.version)
        assertEquals(2, repaired.attempts)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, repaired.status)
        assertEquals("data", destination.getString(destinationPath))
    }

    @Test
    fun `corrupt completed destination is repaired before recording completion`() = runBlocking {
        writeSource()
        val completed = copy()
        destination.setInputStream(destinationPath, "xxxx".byteInputStream(), 4)

        val repaired = copy()

        assertEquals(completed.key, repaired.key)
        assertEquals(2, repaired.attempts)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, repaired.status)
        assertEquals("data", destination.getString(destinationPath))
    }

    @Test
    fun `unavailable completed destination is not invalidated or overwritten`() = runBlocking {
        writeSource()
        val completed = copy()
        val origin = mockk<ObjectStorageService>()
        val target = mockk<ObjectStorageService>()
        val expected = IOException("destination unavailable")
        coEvery { target.getInputStream(any()) } throws expected

        val failure = assertFailsWith<IOException> { copy(origin, target) }

        assertPropagated(expected, failure)
        assertEquals(completed, persisted())
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `corrupt completed payload with unavailable source remains visibly failed`() = runBlocking {
        writeSource()
        copy()
        destination.setInputStream(destinationPath, "xxxx".byteInputStream(), 4)
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } throws IOException("source revision unavailable")

        assertFailsWith<IOException> { copy(origin) }

        val failed = persisted()
        assertEquals(ObjectBackupProgressStatus.FAILED, failed.status)
        assertEquals(2, failed.attempts)
        assertEquals(0L, failed.verifiedBytes)
        assertEquals("source revision unavailable", failed.error)
    }

    @Test
    fun `destination write failure cannot record copied bytes`() = runBlocking {
        writeSource()
        val target = readOnlyDestination()
        val expected = IOException("destination write failed")
        coEvery { target.setInputStream(any(), any(), any()) } throws expected

        val failure = assertFailsWith<IOException> { copy(target = target) }

        assertPropagated(expected, failure)
        assertEquals(ObjectBackupProgressStatus.FAILED, persisted().status)
        assertEquals(0L, persisted().verifiedBytes)
        assertEquals(expected.message, persisted().error)
    }

    @Test
    fun `changed source bytes cannot complete a declared revision`() = runBlocking {
        source.setInputStream(sourcePath, "xxxx".byteInputStream(), 4)

        assertFailsWith<BackupPayloadMismatchException> { copy() }

        assertEquals(ObjectBackupProgressStatus.FAILED, persisted().status)
        assertEquals(0L, persisted().verifiedBytes)
    }

    @Test
    fun `actual coroutine cancellation is recorded and propagated after closing the stream`() = runBlocking {
        var closed = false
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            currentCoroutineContext().cancel(CancellationException("copy cancelled"))
            object : ByteArrayInputStream(data) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        val attempt = async { copy(origin) }

        assertFailsWith<CancellationException> { attempt.await() }

        assertTrue(closed)
        assertEquals(ObjectBackupProgressStatus.FAILED, persisted().status)
        assertEquals(0L, persisted().verifiedBytes)
        assertTrue(assertNotNull(persisted().error).contains("copy cancelled"))
    }

    @Test
    fun `failed completion acknowledgement preserves bytes for a verified retry`() = runBlocking {
        writeSource()
        rejectStatus("completed")

        assertFailsWith<Exception> { copy() }

        assertEquals("data", destination.getString(destinationPath))
        assertEquals(ObjectBackupProgressStatus.FAILED, persisted().status)
        removeStatusRejection()
        val origin = mockk<ObjectStorageService>()
        val target = readOnlyDestination()

        val reconciled = copy(origin, target)

        assertEquals(ObjectBackupProgressStatus.COMPLETED, reconciled.status)
        assertEquals(2, reconciled.attempts)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `failure bookkeeping cannot replace the original storage exception`() = runBlocking {
        rejectStatus("failed")
        val origin = mockk<ObjectStorageService>()
        val expected = IOException("source unavailable")
        coEvery { origin.getInputStream(any()) } throws expected

        val failure = assertFailsWith<IOException> { copy(origin) }

        assertPropagated(expected, failure)
        val recordingFailures = generateSequence(failure as Throwable) { it.cause }
            .flatMap { it.suppressed.asSequence() }
            .toList()
        assertEquals(1, recordingFailures.size)
        assertTrue(recordingFailures.single().message.orEmpty().contains("test checkpoint failure"))
        assertEquals(ObjectBackupProgressStatus.RUNNING, persisted().status)

        removeStatusRejection()
        writeSource()
        assertEquals(ObjectBackupProgressStatus.COMPLETED, copy().status)
    }

    @Test
    fun `conflicting revision intent fails before touching storage`() = runBlocking {
        writeSource()
        val completed = copy()
        val origin = mockk<ObjectStorageService>()
        val target = mockk<ObjectStorageService>()

        assertFailsWith<IllegalArgumentException> {
            copy(origin, target, entry.copy(sha256 = "0".repeat(64)))
        }

        assertEquals(completed, persisted())
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `a changed revision gets independent progress and preserves earlier output`() = runBlocking {
        writeSource()
        val first = copy()
        val changed = entry.copy(revision = "revision-2", payloadPath = "objects/revision-2")

        val second = copy(payload = changed)

        assertNotEquals(first.key, second.key)
        assertEquals(first, persisted())
        assertEquals(ObjectBackupProgressStatus.COMPLETED, second.status)
        assertEquals(1, second.attempts)
        assertEquals("data", destination.getString(destinationPath))
        assertEquals("data", destination.getString(StringObjectPath(changed.payloadPath)))
    }

    private suspend fun copy(
        origin: ObjectStorageService = source,
        target: ObjectStorageService = destination,
        payload: BackupObject = entry,
    ): ObjectBackupProgress = ObjectBackupTransfer(mapOf("content" to origin), target).copy(
        "source-1", "destination-1", "content", payload, progressService,
    )

    private suspend fun prepare(): ObjectBackupProgress =
        progressService.prepare(BACKUP, "source-1", "destination-1", "content", entry)

    private suspend fun persisted(): ObjectBackupProgress = withConnectionManager {
        progressService.list(BACKUP, "source-1", "destination-1", "content", 0, 10).first()
    }

    private suspend fun writeSource() {
        source.setInputStream(sourcePath, data.inputStream(), entry.length)
    }

    private fun readOnlyDestination(): ObjectStorageService = mockk<ObjectStorageService>().also { target ->
        coEvery { target.getInputStream(any()) } coAnswers { destination.getInputStream(firstArg()) }
    }

    private fun storage(name: String): FileSystemObjectStorageService = FileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    private fun rejectStatus(status: String) {
        executeSql("""
            create function backup.reject_test_status() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.status = '$status' then
                    raise exception 'test checkpoint failure';
                end if;
                return new;
            end;
            ${'$'}${'$'};
            create trigger reject_test_status before update on backup.object_progress
                for each row execute function backup.reject_test_status();
        """.trimIndent())
    }

    private fun removeStatusRejection() {
        executeSql("drop trigger reject_test_status on backup.object_progress")
    }

    private fun executeSql(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun assertPropagated(expected: Throwable, failure: Throwable) {
        assertEquals(expected.message, failure.message)
        assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
    }
}
