package bosca.backup

import bosca.backup.ObjectBackupOperation.BACKUP
import bosca.backup.ObjectBackupOperation.RESTORE
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
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

/** Real migration upgrades and persisted recovery runs against independent storage. */
@OptIn(InternalDI::class)
class ObjectBackupRestoreRunIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var service: ObjectBackupRunService
    private lateinit var progress: ObjectBackupProgressService
    private lateinit var directory: Path
    private lateinit var backup: FileSystemObjectStorageService
    private lateinit var target: FileSystemObjectStorageService
    private lateinit var source: FileSystemObjectStorageService
    private lateinit var legacyKey: String
    private val path = "manifests/recovery.json"
    private val first = entry("items/a", "data")
    private val second = entry("items/b", "more")
    private val inventory = ObjectBackupManifest(
        id = "inventory-1", sourceId = "source-1", scope = "content",
        created = "2026-10-16T00:00:00Z", objects = listOf(first, second),
    )
    private val legacyInventory = inventory.copy(id = "legacy-inventory")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_restore_runs").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-restore-run-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()), ownsServices = false)
        provides<BoscaApplication>(singleton = true) { application }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        val initial = object : Migration {
            override val schema = "backup"
            override val resources = listOf("V1__backup_object_progress.sql", "V2__backup_object_runs.sql")
        }
        FlywayMigration(pool).migrate(listOf(initial))
        seedLegacyBackup()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        service = provide()
        progress = provide()

        directory = createTempDirectory("bosca-restore-run-")
        backup = storage("independent-backup")
        target = storage("target")
        source = storage("source")
        write(backup, first.payloadPath, "data")
        write(backup, second.payloadPath, "more")
        write(source, first.key, "data")
        write(source, second.key, "more")
        save()
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
    fun `migration preserves existing backup identities membership and operation`(): Unit = runBlocking {
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        val legacy = assertNotNull(request { service.get(legacyKey) })

        assertEquals(BACKUP, legacy.operation)
        assertEquals(legacy, request { service.prepare(legacyInventory, "legacy-destination") })
        val items = request { service.items(legacyKey, 0, 10) }
        assertEquals(legacyInventory.objects, items.map { it.payload })
        assertTrue(items.all { it.operation == BACKUP })
    }

    @Test
    fun `restore preparation survives restart with immutable input and exact target checkpoints`(): Unit = runBlocking {
        val run = prepare()
        request { progress.prepare(RESTORE, "source-1", "destination-1", "content", entry("other", "x")) }
        BackupProviderRegistrar().register()
        service = provide()

        assertEquals(run, prepare())
        assertEquals(run, request { service.get(run.key) })
        assertEquals(RESTORE, run.operation)
        assertEquals(path, run.path)
        assertEquals(inventory, run.inventory)
        assertEquals(ObjectBackupRunStatus.PENDING, run.status)
        assertEquals(inventory.objects.map { it.copy(payloadPath = it.key) }, request {
            service.items(run.key, 0, 10)
        }.map { it.payload })
        assertEquals(listOf(second.key), request { service.items(run.key, 1, 1) }.map { it.payload.key })
        assertEquals(summary(pending = 2), request { service.summary(run.key) })
        assertFailsWith<IllegalArgumentException> { prepare(manifestPath = "manifests/changed.json") }
        assertFailsWith<IllegalArgumentException> { prepare(inventory.copy(objects = listOf(first))) }
        assertEquals(run, request { service.get(run.key) })
    }

    @Test
    fun `conflicting restore checkpoint rolls back the entire new inventory`(): Unit = runBlocking {
        request {
            progress.prepare(
                RESTORE, "source-1", "destination-1", "content",
                second.copy(payloadPath = second.key, sha256 = "0".repeat(64)),
            )
        }

        assertFailsWith<IllegalArgumentException> { prepare() }

        assertTrue(request {
            service.list(RESTORE, "source-1", "destination-1", "content", 0, 10)
        }.isEmpty())
        assertEquals(listOf(second.key), request {
            progress.list(RESTORE, "source-1", "destination-1", "content", 0, 10)
        }.map { it.payload.key })
    }

    @Test
    fun `invalid target selection and manifest paths cannot prepare a restore`(): Unit = runBlocking {
        for (manifestPath in listOf("", "/absolute", "../outside", "folder//file", first.payloadPath)) {
            assertFailsWith<IllegalArgumentException> { prepare(manifestPath = manifestPath) }
        }
        assertFailsWith<IllegalArgumentException> { prepare(inventory.copy(objects = listOf(first.copy(key = "../outside")))) }
        assertFailsWith<IllegalArgumentException> {
            prepare(inventory.copy(objects = listOf(first, first.copy(revision = "other"))))
        }
        assertFailsWith<IllegalArgumentException> {
            request { service.prepareRestore(inventory, " ", path) }
        }
        assertTrue(request {
            service.list(RESTORE, "source-1", "destination-1", "content", 0, 10)
        }.isEmpty())
    }

    @Test
    fun `duplicate execution cannot take over an active restore writer`(): Unit = runBlocking {
        val run = prepare()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val heldBackup = delegate(backup)
        coEvery { heldBackup.getInputStream(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            backup.getInputStream(firstArg())
        }
        val otherBackup = delegate(backup)
        val otherDestination = delegate(target)
        val otherService = ObjectBackupRunServiceImpl(provide(), progress, provide(), Json, provide())
        val original = async { service.executeRestore(run.key, heldBackup, mapOf("content" to target)) }
        try {
            withTimeout(10_000) { entered.await() }
            val running = assertNotNull(request { service.get(run.key) })
            val items = request { service.items(run.key, 0, 10) }

            val failure = assertFailsWith<IllegalStateException> {
                otherService.executeRestore(run.key, otherBackup, mapOf("content" to otherDestination))
            }

            assertTrue(failure.message.orEmpty().contains("already running"))
            assertEquals(running, request { service.get(run.key) })
            assertEquals(items, request { service.items(run.key, 0, 10) })
            coVerify(exactly = 0) { otherBackup.getInputStream(any()) }
            coVerify(exactly = 0) { otherDestination.getInputStream(any()) }
            coVerify(exactly = 0) { otherDestination.setInputStream(any(), any(), any()) }
            release.complete(Unit)

            val completed = withTimeout(10_000) { original.await() }
            assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
            assertEquals(1, completed.attempts)
            assertEquals(running.version + 1, completed.version)
            assertEquals("data", read(target, first.key))
            assertEquals("more", read(target, second.key))
        } finally {
            release.complete(Unit)
            original.cancelAndJoin()
        }
    }

    @Test
    fun `execution restores declared stores before recording a completed result`(): Unit = runBlocking {
        val selected = inventory.copy(objects = listOf(first, second.copy(store = "artifacts")))
        save(selected)
        val run = prepare(selected)
        val artifacts = storage("artifacts")
        val destination = delegate(target)
        coEvery { destination.setInputStream(any(), any(), any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            assertEquals(ObjectBackupRunStatus.RUNNING, request { service.get(run.key) }?.status)
            assertEquals(2, request { service.items(run.key, 0, 10) }.size)
            target.setInputStream(firstArg(), secondArg(), thirdArg())
        }

        val completed = service.executeRestore(run.key, backup, mapOf("content" to destination, "artifacts" to artifacts))

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(summary(completed = 2), completed.result)
        assertEquals("data", read(target, first.key))
        assertEquals("more", read(artifacts, second.key))
        assertEquals(selected, ObjectBackupManifestStore(backup, Json).load(StringObjectPath(path)))
        assertMissing(target, path)
    }

    @Test
    fun `partial output survives restart and retry reuses completed objects`(): Unit = runBlocking {
        val run = prepare()
        val destination = delegate(target)
        coEvery { destination.setInputStream(match { it.toString() == second.key }, any(), any()) } coAnswers {
            target.setInputStream(firstArg(), "mo".byteInputStream(), 2)
            throw IOException("restore interrupted")
        }
        assertFailsWith<IOException> { execute(run, destination) }
        val partial = assertNotNull(request { service.get(run.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(summary(completed = 1, failed = 1), partial.result)
        BackupProviderRegistrar().register()
        service = provide()
        val resumed = delegate(target)
        coEvery { resumed.setInputStream(match { it.toString() == first.key }, any(), any()) } throws AssertionError(
            "Completed object rewritten",
        )

        val completed = execute(run, resumed)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        assertEquals("more", read(target, second.key))
        coVerify(exactly = 0) { resumed.setInputStream(match { it.toString() == first.key }, any(), any()) }
    }

    @Test
    fun `cancellation remains cancelled on delivery until an explicit retry`(): Unit = runBlocking {
        val run = prepare()
        val destination = delegate(target)
        coEvery { destination.setInputStream(match { it.toString() == second.key }, any(), any()) } coAnswers {
            currentCoroutineContext().cancel(CancellationException("restore cancelled"))
            throw CancellationException("restore cancelled")
        }
        val attempt = async { execute(run, destination) }
        assertFailsWith<CancellationException> { attempt.await() }
        val cancelled = assertNotNull(request { service.get(run.key) })
        assertEquals(ObjectBackupRunStatus.CANCELLED, cancelled.status)
        assertEquals(summary(completed = 1, failed = 1), cancelled.result)
        val unavailable = mockk<ObjectStorageService>()
        assertEquals(cancelled, service.executeRestore(run.key, unavailable, mapOf("content" to unavailable)))
        coVerify(exactly = 0) { unavailable.getInputStream(any()) }
        request { service.retry(run.key, cancelled.version) }

        assertEquals(ObjectBackupRunStatus.COMPLETED, execute(run).status)
    }

    @Test
    fun `queued cancellation returns its historical outcome without storage access`(): Unit = runBlocking {
        val run = prepare()
        val cancelled = request { service.cancel(run.key, run.version, "operator cancelled") }
        val unavailable = mockk<ObjectStorageService>()

        assertEquals(cancelled, service.executeRestore(run.key, unavailable, emptyMap()))
        assertEquals(summary(pending = 2), cancelled.result)
        assertEquals(0, cancelled.attempts)
        coVerify(exactly = 0) { unavailable.getInputStream(any()) }
    }

    @Test
    fun `completed delivery preserves its frozen outcome without rechecking storage`(): Unit = runBlocking {
        val run = prepare()
        val completed = execute(run)
        val checkpoint = request { service.items(run.key, 0, 10) }.first()
        request { progress.invalidate(checkpoint.key, checkpoint.version, "later loss") }
        val unavailable = mockk<ObjectStorageService>()

        assertEquals(completed, service.executeRestore(run.key, unavailable, emptyMap()))
        assertEquals(summary(completed = 2), completed.result)
        assertEquals(summary(completed = 1, failed = 1), request { service.summary(run.key) })
        coVerify(exactly = 0) { unavailable.getInputStream(any()) }
    }

    @Test
    fun `changed input manifest fails before any destination write and remains retryable`(): Unit = runBlocking {
        val run = prepare()
        save(inventory.copy(id = "different-snapshot"))

        assertFailsWith<IllegalStateException> { execute(run) }

        val failed = assertNotNull(request { service.get(run.key) })
        assertEquals(ObjectBackupRunStatus.FAILED, failed.status)
        assertEquals(summary(pending = 2), failed.result)
        assertMissing(target, first.key)
        save()
        assertEquals(ObjectBackupRunStatus.COMPLETED, execute(run).status)
    }

    @Test
    fun `restore uses the captured inventory after manifest validation`(): Unit = runBlocking {
        val run = prepare()
        val recovery = delegate(backup)
        var captured = false
        coEvery { recovery.getInputStream(match { it.toString() == path }) } coAnswers {
            val bytes = backup.getInputStream(StringObjectPath(path)).use { it.readBytes() }
            if (!captured) {
                captured = true
                save(inventory.copy(objects = listOf(first)))
            }
            bytes.inputStream()
        }

        val completed = service.executeRestore(run.key, recovery, mapOf("content" to target))

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(summary(completed = 2), completed.result)
        assertEquals("data", read(target, first.key))
        assertEquals("more", read(target, second.key))
    }

    @Test
    fun `missing or corrupt required backup data cannot complete a restore`(): Unit = runBlocking {
        val run = prepare()
        backup.delete(StringObjectPath(second.payloadPath))
        assertFailsWith<ObjectNotFoundException> { execute(run) }
        assertEquals(ObjectBackupRunStatus.FAILED, request { service.get(run.key) }?.status)
        assertMissing(target, first.key)
        write(backup, second.payloadPath, "oops")
        assertFailsWith<BackupPayloadMismatchException> { execute(run) }
        assertEquals(ObjectBackupRunStatus.FAILED, request { service.get(run.key) }?.status)
        assertMissing(target, first.key)
    }

    @Test
    fun `wrong-operation execution rejects both directions without starting a run`(): Unit = runBlocking {
        val restore = prepare()
        val copy = request { service.prepare(inventory, "destination-1") }
        val unavailable = mockk<ObjectStorageService>()

        assertFailsWith<IllegalArgumentException> { service.execute(restore.key, emptyMap(), unavailable) }
        assertFailsWith<IllegalArgumentException> { service.executeRestore(copy.key, unavailable, emptyMap()) }

        assertEquals(restore, request { service.get(restore.key) })
        assertEquals(copy, request { service.get(copy.key) })
        coVerify(exactly = 0) { unavailable.getInputStream(any()) }
    }

    @Test
    fun `backup and restore histories checkpoints and last success remain separate`(): Unit = runBlocking {
        val restore = prepare()
        val copy = request { service.prepare(inventory, "destination-1") }
        assertNotEquals(copy.key, restore.key)
        val copied = service.execute(copy.key, mapOf("content" to source), target)
        val restored = execute(restore)

        assertEquals(listOf(copied), request {
            service.list(BACKUP, "source-1", "destination-1", "content", 0, 10)
        })
        assertEquals(listOf(restored), request {
            service.list(RESTORE, "source-1", "destination-1", "content", 0, 10)
        })
        assertEquals(copied, request {
            service.lastSuccessful(BACKUP, "source-1", "destination-1", "content")
        })
        assertEquals(restored, request {
            service.lastSuccessful(RESTORE, "source-1", "destination-1", "content")
        })
        val backupKeys = request { service.items(copy.key, 0, 10) }.map { it.key }.toSet()
        val restoreKeys = request { service.items(restore.key, 0, 10) }.map { it.key }.toSet()
        assertTrue(backupKeys.intersect(restoreKeys).isEmpty())
        val laterInventory = inventory.copy(id = "later")
        save(laterInventory)
        val later = prepare(laterInventory)
        val unavailable = mockk<ObjectStorageService>()
        coEvery { unavailable.getInputStream(any()) } throws IOException("target unavailable")
        assertFailsWith<IOException> { execute(later, unavailable) }
        assertEquals(ObjectBackupRunStatus.PARTIAL, request { service.get(later.key) }?.status)
        assertEquals(restored, request {
            service.lastSuccessful(RESTORE, "source-1", "destination-1", "content")
        })
        assertNull(request {
            service.lastSuccessful(RESTORE, "other-source", "destination-1", "content")
        })
    }

    @Test
    fun `stopped running attempt reconciles objects after lost run acknowledgement`(): Unit = runBlocking {
        val run = prepare()
        val running = request { service.start(run.key, run.version) }
        provide<ObjectBackupRestoreService>().restore(StringObjectPath(path), backup, mapOf("content" to target), "destination-1")
        val destination = delegate(target)
        coEvery { destination.setInputStream(any(), any(), any()) } throws AssertionError("Verified output rewritten")

        assertFailsWith<IllegalStateException> { execute(run, destination) }
        assertEquals(running, request { service.get(run.key) })
        coVerify(exactly = 0) { destination.getInputStream(any()) }
        request { service.fail(running.key, running.version, "writer stopped after restoring objects") }
        val completed = execute(run, destination)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        coVerify(exactly = 0) { destination.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `failed completion acknowledgement can retry without writing restored files again`(): Unit = runBlocking {
        val run = prepare()
        rejectStatus("completed")
        assertFailsWith<Exception> { execute(run) }
        val partial = assertNotNull(request { service.get(run.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(summary(completed = 2), partial.result)
        removeStatusRejection()
        val destination = delegate(target)
        coEvery { destination.setInputStream(any(), any(), any()) } throws AssertionError("Restored output rewritten")

        assertEquals(ObjectBackupRunStatus.COMPLETED, execute(run, destination).status)
        coVerify(exactly = 0) { destination.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `bookkeeping failure preserves the storage error and recoverable run`(): Unit = runBlocking {
        val run = prepare()
        rejectStatus("failed")
        val destination = delegate(target)
        val expected = IOException("target disconnected")
        coEvery { destination.getInputStream(any()) } throws expected

        val failure = assertFailsWith<IOException> { execute(run, destination) }

        assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it === expected })
        assertTrue(generateSequence(failure as Throwable) { it.cause }.flatMap { it.suppressed.asSequence() }.any {
            it.message.orEmpty().contains("run checkpoint failure")
        })
        val running = assertNotNull(request { service.get(run.key) })
        assertEquals(ObjectBackupRunStatus.RUNNING, running.status)
        removeStatusRejection()
        assertFailsWith<IllegalStateException> { execute(run) }
        assertEquals(running, request { service.get(run.key) })
        assertFailsWith<IllegalStateException> {
            request { service.fail(running.key, running.version + 1, "writer stopped") }
        }
        request { service.fail(running.key, running.version, "writer stopped") }
        assertEquals(ObjectBackupRunStatus.COMPLETED, execute(run).status)
    }

    @Test
    fun `empty restore completes with zero totals and no destination writes`(): Unit = runBlocking {
        val selected = inventory.copy(objects = emptyList())
        save(selected)
        val run = prepare(selected)
        val destination = mockk<ObjectStorageService>()

        val completed = execute(run, destination)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(ObjectBackupRunSummary(0, 0, 0, 0, 0, 0, 0), completed.result)
        coVerify(exactly = 0) { destination.setInputStream(any(), any(), any()) }
    }

    private suspend fun prepare(
        selected: ObjectBackupManifest = inventory,
        manifestPath: String = path,
    ): ObjectBackupRun = request { service.prepareRestore(selected, "destination-1", manifestPath) }

    private suspend fun execute(run: ObjectBackupRun, destination: ObjectStorageService = target): ObjectBackupRun =
        service.executeRestore(run.key, backup, mapOf("content" to destination))

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private fun summary(
        pending: Long = 0,
        completed: Long = 0,
        failed: Long = 0,
    ): ObjectBackupRunSummary = ObjectBackupRunSummary(2, pending, 0, completed, failed, 8, completed * 4)

    private suspend fun save(selected: ObjectBackupManifest = inventory) {
        ObjectBackupManifestStore(backup, Json).save(StringObjectPath(path), selected)
    }

    private fun delegate(storage: ObjectStorageService): ObjectStorageService =
        mockk<ObjectStorageService>().also { value ->
            coEvery { value.getInputStream(any()) } coAnswers { storage.getInputStream(firstArg()) }
            coEvery { value.setInputStream(any(), any(), any()) } coAnswers {
                storage.setInputStream(firstArg(), secondArg(), thirdArg())
            }
        }

    private fun storage(name: String): FileSystemObjectStorageService = FileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    private suspend fun write(storage: ObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }

    private suspend fun read(storage: ObjectStorageService, key: String): String =
        storage.getInputStream(StringObjectPath(key)).use { it.readBytes().decodeToString() }

    private suspend fun assertMissing(storage: ObjectStorageService, key: String) {
        assertFailsWith<ObjectNotFoundException> { storage.getInputStream(StringObjectPath(key)) }
    }

    private fun entry(key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject("content", key, "revision-1", bytes.size.toLong(), digest, "objects/$digest")
    }

    private fun identity(parts: List<String>): String = MessageDigest.getInstance("SHA-256").digest(
        Json.encodeToString(ListSerializer(String.serializer()), parts).toByteArray(),
    ).toHexString()

    private fun seedLegacyBackup() {
        legacyKey = identity(listOf(legacyInventory.sourceId, "legacy-destination", legacyInventory.scope, legacyInventory.id))
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("""
                insert into backup.object_runs (key, source_id, destination_id, scope, inventory, path)
                values (?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent()).use { statement ->
                statement.setString(1, legacyKey)
                statement.setString(2, legacyInventory.sourceId)
                statement.setString(3, "legacy-destination")
                statement.setString(4, legacyInventory.scope)
                statement.setString(5, Json.encodeToString(ObjectBackupManifest.serializer(), legacyInventory))
                statement.setString(6, "manifests/$legacyKey.json")
                statement.executeUpdate()
            }
            for ((position, entry) in legacyInventory.objects.withIndex()) {
                val key = identity(listOf(
                    legacyInventory.sourceId, "legacy-destination", legacyInventory.scope, entry.store, entry.key, entry.revision,
                ))
                connection.prepareStatement("""
                    insert into backup.object_progress (key, source_id, destination_id, scope, payload)
                    values (?, ?, ?, ?, ?::jsonb)
                """.trimIndent()).use { statement ->
                    statement.setString(1, key)
                    statement.setString(2, legacyInventory.sourceId)
                    statement.setString(3, "legacy-destination")
                    statement.setString(4, legacyInventory.scope)
                    statement.setString(5, Json.encodeToString(BackupObject.serializer(), entry))
                    statement.executeUpdate()
                }
                connection.prepareStatement("insert into backup.object_run_items (run_key, position, progress_key) values (?, ?, ?)").use {
                    it.setString(1, legacyKey)
                    it.setInt(2, position)
                    it.setString(3, key)
                    it.executeUpdate()
                }
            }
        }
    }

    private fun rejectStatus(status: String) {
        sql("""
            create function backup.reject_restore_run_status() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.status = '$status' then raise exception 'run checkpoint failure'; end if;
                return new;
            end;
            ${'$'}${'$'};
            create trigger reject_restore_run_status before update on backup.object_runs
                for each row execute function backup.reject_restore_run_status();
        """.trimIndent())
    }

    private fun removeStatusRejection() {
        sql("drop trigger reject_restore_run_status on backup.object_runs")
        sql("drop function backup.reject_restore_run_status()")
    }

    private fun sql(value: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(value) }
        }
    }
}
