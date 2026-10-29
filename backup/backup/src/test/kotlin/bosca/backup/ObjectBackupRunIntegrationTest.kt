package bosca.backup

import bosca.backup.ObjectBackupOperation.BACKUP
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.StorageProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.service.ManagedFileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
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

/** Exercises registered run persistence, execution and publication with real database/storage state. */
@OptIn(InternalDI::class)
class ObjectBackupRunIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var service: ObjectBackupRunService
    private lateinit var progress: ObjectBackupProgressService
    private lateinit var directory: Path
    private lateinit var source: ManagedFileSystemObjectStorageService
    private lateinit var destination: ManagedFileSystemObjectStorageService
    private val first = entry("a", "data")
    private val second = entry("b", "more")
    private val inventory = ObjectBackupManifest(
        id = "inventory-1",
        sourceId = "source-1",
        scope = "content",
        created = "2026-10-16T00:00:00Z",
        objects = listOf(first, second),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_runs").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-run-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()), ownsServices = false)
        provides<BoscaApplication>(singleton = true) { application }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        service = provide()
        progress = provide()

        directory = createTempDirectory("bosca-object-run-")
        source = storage("source")
        destination = storage("destination")
        write(source, first.key, "data")
        write(source, second.key, "more")
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `registered run migration is repeatable and creates run associations`(): Unit = runBlocking {
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    select to_regclass('backup.object_runs') is not null
                        and to_regclass('backup.object_run_items') is not null
                """.trimIndent()).use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1))
                }
            }
        }
    }

    @Test
    fun `prepared run survives restart with exact inventory and ordered checkpoints`(): Unit = runBlocking {
        val run = request { service.prepare(inventory, "destination-1") }
        request { progress.prepare(BACKUP, inventory.sourceId, "destination-1", inventory.scope, entry("unrelated", "x")) }
        BackupProviderRegistrar().register()
        service = provide()

        assertEquals(run, request { service.get(run.key) })
        assertEquals(run, request { service.prepare(inventory, "destination-1") })
        assertEquals(inventory, run.inventory)
        assertEquals(ObjectBackupRunStatus.PENDING, run.status)
        assertEquals(0, run.attempts)
        assertNull(run.result)
        assertEquals(listOf(first, second), request { service.items(run.key, 0, 10) }.map { it.payload })
        assertEquals(listOf(second), request { service.items(run.key, 1, 1) }.map { it.payload })
        assertEquals(summary(pending = 2), request { service.summary(run.key) })
    }

    @Test
    fun `conflicting intent rolls back new run and checkpoint associations`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(inventory.copy(objects = listOf(first)), "destination-1") }
        }
        assertEquals(prepared, request { service.get(prepared.key) })

        val newFirst = entry("c", "next")
        val conflicting = second.copy(sha256 = "0".repeat(64))
        assertFailsWith<IllegalArgumentException> {
            request {
                service.prepare(
                    inventory.copy(id = "inventory-2", objects = listOf(newFirst, conflicting)),
                    "destination-1",
                )
            }
        }

        assertEquals(listOf(prepared), request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) })
        assertEquals(setOf(first, second), request {
            progress.list(BACKUP, "source-1", "destination-1", "content", 0, 10)
        }.map { it.payload }.toSet())
    }

    @Test
    fun `invalid inventory identity totals and pagination fail without creating runs`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { request { service.prepare(inventory, " ") } }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepare(inventory.copy(objects = listOf(first, first)), "destination-1") }
        }
        assertFailsWith<IllegalArgumentException> {
            request {
                service.prepare(inventory.copy(objects = listOf(
                    first.copy(length = Long.MAX_VALUE), second.copy(length = 1),
                )), "destination-1")
            }
        }
        assertFailsWith<IllegalArgumentException> { request { service.items("missing", -1, 10) } }
        assertFailsWith<IllegalArgumentException> {
            request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 0) }
        }
        assertTrue(request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 10) }.isEmpty())
        assertNull(request { service.get("missing") })
        assertFailsWith<IllegalStateException> { request { service.summary("missing") } }
    }

    @Test
    fun `run cannot complete until all declared object checkpoints are verified`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        assertFailsWith<IllegalStateException> { request { service.complete(prepared.key, prepared.version) } }
        assertFailsWith<IllegalStateException> {
            request { service.fail(prepared.key, prepared.version, "not started") }
        }
        val running = request { service.start(prepared.key, prepared.version) }

        assertFailsWith<IllegalStateException> { request { service.complete(running.key, running.version) } }
        val items = request { service.items(running.key, 0, 10) }
        acknowledge(items.first())
        assertFailsWith<IllegalStateException> { request { service.complete(running.key, running.version) } }
        acknowledge(items.last())

        val completed = request { service.complete(running.key, running.version) }

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(summary(completed = 2), completed.result)
        assertFailsWith<IllegalStateException> { request { service.start(completed.key, completed.version) } }
    }

    @Test
    fun `competing starts and stale acknowledgements cannot replace a newer run attempt`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val attempts = List(2) {
            async {
                try {
                    request { service.start(prepared.key, prepared.version) }
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }.awaitAll()
        assertEquals(1, attempts.count { it != null })
        val firstAttempt = attempts.filterNotNull().single()
        val failed = request { service.fail(firstAttempt.key, firstAttempt.version, "worker stopped") }
        assertEquals(ObjectBackupRunStatus.FAILED, failed.status)
        val retry = request { service.start(failed.key, failed.version) }
        assertEquals(2, retry.attempts)

        assertFailsWith<IllegalStateException> {
            request { service.complete(firstAttempt.key, firstAttempt.version) }
        }
        assertEquals(retry, request { service.get(retry.key) })
    }

    @Test
    fun `duplicate execution cannot take over an active backup writer`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val origin = mockk<ManagedObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            source.getInputStream(firstArg())
        }
        val otherSource = mockk<ManagedObjectStorageService>()
        coEvery { otherSource.getInputStream(any()) } coAnswers { source.getInputStream(firstArg()) }
        val otherDestination = delegatingDestination()
        val otherService = ObjectBackupRunServiceImpl(provide(), progress, provide(), Json, provide())
        val original = async { execute(prepared, origin) }
        try {
            withTimeout(10_000) { entered.await() }
            val running = assertNotNull(request { service.get(prepared.key) })
            val items = request { service.items(prepared.key, 0, 10) }

            val failure = assertFailsWith<IllegalStateException> {
                otherService.execute(prepared.key, mapOf("content" to otherSource), otherDestination)
            }

            assertTrue(failure.message.orEmpty().contains("already running"))
            assertEquals(running, request { service.get(prepared.key) })
            assertEquals(items, request { service.items(prepared.key, 0, 10) })
            coVerify(exactly = 0) { otherSource.getInputStream(any()) }
            coVerify(exactly = 0) { otherDestination.getInputStream(any()) }
            coVerify(exactly = 0) { otherDestination.setInputStream(any(), any(), any()) }
            release.complete(Unit)

            val completed = withTimeout(10_000) { original.await() }
            assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
            assertEquals(1, completed.attempts)
            assertEquals(running.version + 1, completed.version)
        } finally {
            release.complete(Unit)
            original.cancelAndJoin()
        }
    }

    @Test
    fun `execution publishes a verified manifest before recording completed outcome`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val target = delegatingDestination { path ->
            assertEquals(0, pool.activeConnections)
            assertEquals(ObjectBackupRunStatus.RUNNING, request { service.get(prepared.key) }?.status)
            if (path == prepared.path) {
                assertEquals(summary(completed = 2), request { service.summary(prepared.key) })
            }
        }

        val completed = execute(prepared, target = target)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(summary(completed = 2), completed.result)
        assertEquals(1, completed.attempts)
        assertEquals(completed, request { service.get(prepared.key) })
        val manifests = ObjectBackupManifestStore(destination, Json)
        assertEquals(inventory, manifests.load(StringObjectPath(completed.path)))
        manifests.verify(inventory)
        assertEquals(completed, request { service.lastSuccessful(BACKUP, "source-1", "destination-1", "content") })
    }

    @Test
    fun `a newer partial run does not replace the previous successful inventory`(): Unit = runBlocking {
        val previous = request {
            service.prepare(inventory.copy(id = "previous", objects = listOf(first)), "destination-1")
        }
        val successful = execute(previous)
        val later = request { service.prepare(inventory, "destination-1") }
        val origin = mockk<ManagedObjectStorageService>()
        coEvery { origin.getInputStream(any()) } throws IOException("source unavailable")

        assertFailsWith<IOException> { execute(later, origin) }

        val partial = assertNotNull(request { service.get(later.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(summary(completed = 1, failed = 1), partial.result)
        assertEquals("source unavailable", partial.error)
        assertEquals(successful, request { service.lastSuccessful(BACKUP, "source-1", "destination-1", "content") })
        assertEquals(listOf(partial, successful), request {
            service.list(BACKUP, "source-1", "destination-1", "content", 0, 10)
        })
        assertFailsWith<ObjectNotFoundException> { destination.getInputStream(StringObjectPath(partial.path)) }
    }

    @Test
    fun `cancellation has its own result and a retry reuses completed bytes`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val origin = mockk<ManagedObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            if (firstArg<StringObjectPath>().toString() == second.key) {
                currentCoroutineContext().cancel(CancellationException("run cancelled"))
            }
            source.getInputStream(firstArg())
        }
        val attempt = async { execute(prepared, origin) }

        assertFailsWith<CancellationException> { attempt.await() }

        val cancelled = assertNotNull(request { service.get(prepared.key) })
        assertEquals(ObjectBackupRunStatus.CANCELLED, cancelled.status)
        assertEquals(summary(completed = 1, failed = 1), cancelled.result)
        val resumedOrigin = mockk<ManagedObjectStorageService>()
        coEvery { resumedOrigin.getInputStream(any()) } coAnswers {
            assertEquals(second.key, firstArg<StringObjectPath>().toString())
            source.getInputStream(firstArg())
        }
        request { service.retry(cancelled.key, cancelled.version) }

        val completed = execute(cancelled, resumedOrigin)

        assertEquals(prepared.key, completed.key)
        assertEquals(prepared.created, completed.created)
        assertEquals(2, completed.attempts)
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertNull(completed.error)
    }

    @Test
    fun `a queued run can be cancelled before any writer starts`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }

        val cancelled = request { service.cancel(prepared.key, prepared.version, "operator cancelled") }

        assertEquals(ObjectBackupRunStatus.CANCELLED, cancelled.status)
        assertEquals(0, cancelled.attempts)
        assertEquals(summary(pending = 2), cancelled.result)
        assertEquals(cancelled, request { service.get(prepared.key) })
    }

    @Test
    fun `cancelled redelivery remains cancelled until an explicit retry`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val running = request { service.start(prepared.key, prepared.version) }
        val cancelled = request { service.cancel(running.key, running.version, "operator cancelled") }
        val origin = mockk<ManagedObjectStorageService>()
        val target = mockk<ManagedObjectStorageService>()

        assertEquals(cancelled, execute(cancelled, origin, target))

        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `stopped run reuses a published manifest and payloads after a lost acknowledgement`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val running = request { service.start(prepared.key, prepared.version) }
        ObjectBackupTransfer(mapOf("content" to source), destination).copy(inventory, "destination-1", progress)
        ObjectBackupManifestStore(destination, Json).save(StringObjectPath(prepared.path), inventory)
        val origin = mockk<ManagedObjectStorageService>()
        val target = delegatingDestination { error("Already published output must not be rewritten") }

        assertFailsWith<IllegalStateException> { execute(prepared, origin, target) }
        assertEquals(running, request { service.get(prepared.key) })
        coVerify(exactly = 0) { target.getInputStream(any()) }
        request { service.fail(running.key, running.version, "writer stopped after publishing manifest") }
        val completed = execute(prepared, origin, target)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `partial manifest write fails and retry repairs publication without source reads`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val target = delegatingDestination()
        coEvery { target.setInputStream(match { it.toString() == prepared.path }, any(), any()) } coAnswers {
            destination.setInputStream(firstArg(), "{".byteInputStream(), 1)
            throw IOException("manifest write interrupted")
        }

        assertFailsWith<IOException> { execute(prepared, target = target) }

        val partial = assertNotNull(request { service.get(prepared.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(summary(completed = 2), partial.result)
        val origin = mockk<ManagedObjectStorageService>()

        val completed = execute(partial, origin)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(inventory, ObjectBackupManifestStore(destination, Json).load(StringObjectPath(completed.path)))
        coVerify(exactly = 0) { origin.getInputStream(any()) }
    }

    @Test
    fun `acknowledged but corrupt manifest cannot complete a run`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val target = delegatingDestination()
        coEvery { target.setInputStream(match { it.toString() == prepared.path }, any(), any()) } coAnswers {
            destination.setInputStream(firstArg(), "{".byteInputStream(), 1)
            thirdArg<Long>()
        }

        assertFailsWith<Exception> { execute(prepared, target = target) }

        assertEquals(ObjectBackupRunStatus.PARTIAL, request { service.get(prepared.key) }?.status)
        assertNull(request { service.lastSuccessful(BACKUP, "source-1", "destination-1", "content") })
    }

    @Test
    fun `failed completion acknowledgement retains a valid inventory for retry`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        rejectStatus("completed")

        assertFailsWith<Exception> { execute(prepared) }

        val partial = assertNotNull(request { service.get(prepared.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(inventory, ObjectBackupManifestStore(destination, Json).load(StringObjectPath(partial.path)))
        removeStatusRejection()
        val origin = mockk<ManagedObjectStorageService>()
        val target = delegatingDestination { error("Verified output must be reused") }

        assertEquals(ObjectBackupRunStatus.COMPLETED, execute(partial, origin, target).status)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
    }

    @Test
    fun `a conflicting published manifest cannot be overwritten or acknowledged`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val other = inventory.copy(id = "unrelated", objects = emptyList())
        ObjectBackupManifestStore(destination, Json).save(StringObjectPath(prepared.path), other)

        assertFailsWith<IllegalStateException> { execute(prepared) }

        assertEquals(other, ObjectBackupManifestStore(destination, Json).load(StringObjectPath(prepared.path)))
        assertEquals(ObjectBackupRunStatus.PARTIAL, request { service.get(prepared.key) }?.status)
    }

    @Test
    fun `failed run bookkeeping preserves the storage failure and recoverable running state`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        rejectStatus("failed")
        val origin = mockk<ManagedObjectStorageService>()
        val expected = IOException("source disconnected")
        coEvery { origin.getInputStream(any()) } throws expected

        val failure = assertFailsWith<IOException> { execute(prepared, origin) }

        assertEquals(expected.message, failure.message)
        assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it === expected })
        val recordingFailures = generateSequence(failure as Throwable) { it.cause }
            .flatMap { it.suppressed.asSequence() }
            .toList()
        assertEquals(1, recordingFailures.size)
        assertTrue(recordingFailures.single().message.orEmpty().contains("run checkpoint failure"))
        val running = assertNotNull(request { service.get(prepared.key) })
        assertEquals(ObjectBackupRunStatus.RUNNING, running.status)
        removeStatusRejection()

        assertFailsWith<IllegalStateException> { execute(prepared) }
        assertEquals(running, request { service.get(prepared.key) })
        assertFailsWith<IllegalStateException> {
            request { service.fail(running.key, running.version + 1, "writer stopped") }
        }
        request { service.fail(running.key, running.version, "writer stopped") }
        assertEquals(ObjectBackupRunStatus.COMPLETED, execute(prepared).status)
    }

    @Test
    fun `completed redelivery returns its durable outcome without accessing storage`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val completed = execute(prepared)
        val origin = mockk<ManagedObjectStorageService>()
        val target = mockk<ManagedObjectStorageService>()

        assertEquals(completed, execute(prepared, origin, target))
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `terminal result is frozen separately from later shared checkpoint health`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory, "destination-1") }
        val completed = execute(prepared)
        val checkpoint = request { service.items(completed.key, 0, 10) }.first()
        request { progress.invalidate(checkpoint.key, checkpoint.version, "later storage loss") }

        assertEquals(completed, request { service.get(completed.key) })
        assertEquals(summary(completed = 2), completed.result)
        assertEquals(summary(completed = 1, failed = 1), request { service.summary(completed.key) })
    }

    @Test
    fun `run listings isolate source destination scope with stable offset pagination`(): Unit = runBlocking {
        val firstRun = request { service.prepare(inventory, "destination-1") }
        val secondRun = request { service.prepare(inventory.copy(id = "inventory-2"), "destination-1") }
        request { service.prepare(inventory.copy(sourceId = "other-source"), "destination-1") }
        request { service.prepare(inventory, "other-destination") }
        request { service.prepare(inventory.copy(scope = "other-scope"), "destination-1") }

        assertNotEquals(firstRun.key, secondRun.key)
        assertEquals(listOf(secondRun), request { service.list(BACKUP, "source-1", "destination-1", "content", 0, 1) })
        assertEquals(listOf(firstRun), request { service.list(BACKUP, "source-1", "destination-1", "content", 1, 10) })
        assertNull(request { service.lastSuccessful(BACKUP, "source-1", "destination-1", "content") })
    }

    @Test
    fun `empty inventory publishes a valid completed result with zero totals`(): Unit = runBlocking {
        val prepared = request { service.prepare(inventory.copy(objects = emptyList()), "destination-1") }

        val completed = execute(prepared)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(ObjectBackupRunSummary(0, 0, 0, 0, 0, 0, 0), completed.result)
        assertTrue(request { service.items(completed.key, 0, 10) }.isEmpty())
    }

    @Test
    fun `successful object run emits its published manifest and completed replay emits again`(): Unit = runBlocking {
        val events = BackupEventTestDispatcher()
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) { events }
        val prepared = request { service.prepare(inventory, "retained") }
        events.beforeDispatch = { event ->
            assertEquals(ObjectBackupRunStatus.COMPLETED, service.get(event.key)?.status)
            assertEquals(inventory, ObjectBackupManifestStore(destination, Json).load(StringObjectPath(event.path)))
        }
        execute(prepared)
        val event = BackupCompleted(prepared.key, BackupType.OBJECTS, "retained", prepared.path)
        assertEquals(listOf(event), events.events)
        execute(prepared)
        assertEquals(listOf(event, event), events.events)
    }

    @Test
    fun `failed object transfer emits no completion event`(): Unit = runBlocking {
        val events = BackupEventTestDispatcher()
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) { events }
        val prepared = request { service.prepare(inventory, "retained") }
        val failing = delegatingDestination { throw IOException("upload failed") }
        assertFailsWith<IOException> { execute(prepared, target = failing) }
        assertTrue(events.events.isEmpty())
        assertEquals(ObjectBackupRunStatus.FAILED, request { service.get(prepared.key) }?.status)
    }

    private suspend fun execute(
        run: ObjectBackupRun,
        origin: ManagedObjectStorageService = source,
        target: ManagedObjectStorageService = destination,
    ): ObjectBackupRun = service.execute(
        run.key,
        mapOf("content" to origin),
        target,
    )

    private suspend fun acknowledge(item: ObjectBackupProgress) = request {
        val running = progress.start(item.key, item.version)
        progress.complete(running.key, running.version)
    }

    private fun summary(
        pending: Long = 0,
        running: Long = 0,
        completed: Long = 0,
        failed: Long = 0,
    ): ObjectBackupRunSummary = ObjectBackupRunSummary(2, pending, running, completed, failed, 8, completed * 4)

    private fun delegatingDestination(beforeWrite: suspend (String) -> Unit = {}): ManagedObjectStorageService =
        mockk<ManagedObjectStorageService>().also { target ->
            coEvery { target.getInputStream(any()) } coAnswers { destination.getInputStream(firstArg()) }
            coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
                beforeWrite(firstArg<StringObjectPath>().toString())
                destination.setInputStream(firstArg(), secondArg(), thirdArg())
            }
        }

    private fun storage(name: String): ManagedFileSystemObjectStorageService = ManagedFileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    private suspend fun write(storage: ManagedObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }

    private fun entry(key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        return BackupObject(
            store = "content", key = "items/$key", revision = "$key-1", length = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(), payloadPath = "objects/$key-1",
        )
    }

    private fun rejectStatus(status: String) {
        executeSql("""
            create function backup.reject_run_status() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.status = '$status' then
                    raise exception 'run checkpoint failure';
                end if;
                return new;
            end;
            ${'$'}${'$'};
            create trigger reject_run_status before update on backup.object_runs
                for each row execute function backup.reject_run_status();
        """.trimIndent())
    }

    private fun removeStatusRejection() {
        executeSql("drop trigger reject_run_status on backup.object_runs")
    }

    private fun executeSql(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)
}
