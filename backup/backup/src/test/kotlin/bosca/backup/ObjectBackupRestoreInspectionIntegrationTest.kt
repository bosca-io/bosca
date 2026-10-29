package bosca.backup

import bosca.backup.ObjectBackupOperation.RESTORE
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
import bosca.storage.service.ManagedFileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real recovery storage, generated services and a separate progress database. */
@OptIn(InternalDI::class)
class ObjectBackupRestoreInspectionIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var service: ObjectBackupRestoreService
    private lateinit var progress: ObjectBackupProgressService
    private lateinit var directory: Path
    private lateinit var backup: ManagedFileSystemObjectStorageService
    private lateinit var content: ManagedFileSystemObjectStorageService
    private lateinit var artifacts: ManagedFileSystemObjectStorageService
    private val path = StringObjectPath("manifests/recovery.json")
    private val first = entry("content", "metadata/a/1/content", "data")
    private val second = entry("artifacts", "packages/b", "more")
    private val inventory = ObjectBackupManifest(
        id = "snapshot-1",
        sourceId = "old-instance",
        scope = "content-and-artifacts",
        created = "2026-10-16T00:00:00Z",
        objects = listOf(first, second),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_restore_inspection").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-restore-inspection-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        service = provide()
        progress = provide()

        directory = createTempDirectory("bosca-object-restore-")
        backup = storage("independent-backup")
        content = storage("restored-content")
        artifacts = storage("restored-artifacts")
        write(backup, first.payloadPath, "data")
        write(backup, second.payloadPath, "more")
        save()
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `missing objects are reported in inventory order without creating progress`(): Unit = runBlocking {
        val inspection = inspect()

        assertEquals(inventory, inspection.inventory)
        assertEquals(inventory.objects, inspection.objects.map { it.payload })
        assertEquals(List(2) { ObjectBackupRestoreObjectStatus.MISSING }, inspection.objects.map { it.status })
        assertTrue(inspection.objects.all { it.progress == null })
        assertTrue(checkpoints().isEmpty())
        assertMissing(content, first.key)
        assertMissing(artifacts, second.key)
    }

    @Test
    fun `matching and conflicting destination data are both reported without writes`(): Unit = runBlocking {
        write(content, first.key, "data")
        write(artifacts, second.key, "independent data")
        val target = delegate(artifacts)

        val inspection = inspect(artifactTarget = target)

        assertEquals(
            listOf(ObjectBackupRestoreObjectStatus.MATCHING, ObjectBackupRestoreObjectStatus.DIFFERENT),
            inspection.objects.map { it.status },
        )
        assertEquals("independent data", read(artifacts, second.key))
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `failed earlier output is repairable without changing its checkpoint`(): Unit = runBlocking {
        val failed = withConnectionManager {
            val prepared = progress.prepare(RESTORE, inventory.sourceId, "new-instance", inventory.scope, restorePayload(first))
            val running = progress.start(prepared.key, prepared.version)
            progress.fail(running.key, running.version, "interrupted")
        }
        write(content, first.key, "da")

        val item = inspect().objects.first()

        assertEquals(ObjectBackupRestoreObjectStatus.DIFFERENT, item.status)
        assertEquals(failed, item.progress)
        assertEquals(listOf(failed), checkpoints())
        assertEquals("da", read(content, first.key))
    }

    @Test
    fun `pending intent does not authorize overwriting conflicting data`(): Unit = runBlocking {
        val pending = withConnectionManager {
            progress.prepare(RESTORE, inventory.sourceId, "new-instance", inventory.scope, restorePayload(first))
        }
        write(content, first.key, "other")

        val item = inspect().objects.first()

        assertEquals(ObjectBackupRestoreObjectStatus.DIFFERENT, item.status)
        assertEquals(pending, item.progress)
        assertEquals(listOf(pending), checkpoints())
    }

    @Test
    fun `completed checkpoint is preserved while actual missing output is reported`(): Unit = runBlocking {
        service.restore(path, backup, targets(), "new-instance")
        val before = checkpoints()
        artifacts.delete(StringObjectPath(second.key))

        val inspection = inspect()

        assertEquals(
            listOf(ObjectBackupRestoreObjectStatus.MATCHING, ObjectBackupRestoreObjectStatus.MISSING),
            inspection.objects.map { it.status },
        )
        assertTrue(inspection.objects.all { it.progress?.status == ObjectBackupProgressStatus.COMPLETED })
        assertEquals(before, checkpoints())
        assertMissing(artifacts, second.key)
    }

    @Test
    fun `corrupt required backup data fails before destination writes`(): Unit = runBlocking {
        write(backup, second.payloadPath, "oops")
        val target = delegate(content)

        assertFailsWith<BackupPayloadMismatchException> { inspect(contentTarget = target) }

        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `missing backup payload and malformed manifest fail inspection without progress writes`(): Unit = runBlocking {
        backup.delete(StringObjectPath(second.payloadPath))
        assertFailsWith<ObjectNotFoundException> { inspect() }
        write(backup, path.toString(), "{")
        assertFailsWith<SerializationException> { inspect() }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `destination access errors propagate without classifying them as missing`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.getInputStream(any()) } throws IOException("destination unavailable")

        assertFailsWith<IOException> { inspect(artifactTarget = target) }

        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `destination cancellation propagates without creating progress`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.getInputStream(any()) } throws CancellationException("inspection cancelled")

        assertFailsWith<CancellationException> { inspect(artifactTarget = target) }

        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `mapping validation precedes destination reads and progress creation`(): Unit = runBlocking {
        val target = mockk<ManagedObjectStorageService>()
        assertFailsWith<IllegalArgumentException> {
            service.inspect(path, backup, mapOf("content" to target), "new-instance")
        }
        save(inventory.copy(objects = listOf(first.copy(key = "../outside"))))
        assertFailsWith<IllegalArgumentException> { inspect(contentTarget = target) }
        assertFailsWith<IllegalArgumentException> {
            service.inspect(path, backup, targets(), " ")
        }
        coVerify(exactly = 0) { target.getInputStream(any()) }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `storage reads run without an active database connection`(): Unit = runBlocking {
        val target = delegate(content)
        coEvery { target.getInputStream(any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            content.getInputStream(firstArg())
        }

        inspect(contentTarget = target)

        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `a restore checks storage again after an earlier inspection`(): Unit = runBlocking {
        assertTrue(inspect().objects.all { it.status == ObjectBackupRestoreObjectStatus.MISSING })
        write(artifacts, second.key, "new independent data")

        assertEquals(inventory, service.restore(path, backup, targets(), "new-instance"))
        assertEquals("more", read(artifacts, second.key))
        assertEquals("data", read(content, first.key))
        assertTrue(checkpoints().all { it.status == ObjectBackupProgressStatus.COMPLETED })
    }

    @Test
    fun `conflicting revision claims are rejected without changing earlier progress`(): Unit = runBlocking {
        val prepared = withConnectionManager {
            progress.prepare(
                RESTORE,
                inventory.sourceId, "new-instance", inventory.scope, restorePayload(first).copy(length = 5),
            )
        }

        assertFailsWith<IllegalArgumentException> { inspect() }

        assertEquals(listOf(prepared), checkpoints())
        assertMissing(content, first.key)
    }

    @Test
    fun `empty inventory inspection returns no objects or progress`(): Unit = runBlocking {
        save(inventory.copy(objects = emptyList()))

        val inspection = inspect()

        assertTrue(inspection.inventory.objects.isEmpty())
        assertTrue(inspection.objects.isEmpty())
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `selected inventory needs no separate manifest and creates no progress`(): Unit = runBlocking {
        backup.delete(path)

        val inspection = service.inspect(inventory, backup, targets(), "new-instance")

        assertEquals(inventory, inspection.inventory)
        assertEquals(inventory.objects, inspection.objects.map { it.payload })
        assertEquals(List(2) { ObjectBackupRestoreObjectStatus.MISSING }, inspection.objects.map { it.status })
        assertTrue(inspection.objects.all { it.progress == null })
        assertTrue(checkpoints().isEmpty())
        assertMissing(content, first.key)
        assertMissing(artifacts, second.key)
        assertMissing(backup, path.toString())
    }

    @Test
    fun `selected inventory reports target conflicts and owned repairs without changing data or progress`(): Unit = runBlocking {
        val failed = withConnectionManager {
            val prepared = progress.prepare(RESTORE, inventory.sourceId, "new-instance", inventory.scope, restorePayload(first))
            val running = progress.start(prepared.key, prepared.version)
            progress.fail(running.key, running.version, "interrupted")
        }
        write(content, first.key, "da")
        write(artifacts, second.key, "independent data")
        BackupProviderRegistrar().register()

        val inspection = provide<ObjectBackupRestoreService>().inspect(inventory, backup, targets(), "new-instance")

        assertEquals(
            listOf(ObjectBackupRestoreObjectStatus.DIFFERENT, ObjectBackupRestoreObjectStatus.DIFFERENT),
            inspection.objects.map { it.status },
        )
        assertEquals(failed, inspection.objects.first().progress)
        assertEquals(listOf(failed), checkpoints())
        assertEquals("da", read(content, first.key))
        assertEquals("independent data", read(artifacts, second.key))
    }

    @Test
    fun `selected inventory validates identity keys revisions and mappings before storage access`(): Unit = runBlocking {
        val retained = mockk<ManagedObjectStorageService>()
        val target = mockk<ManagedObjectStorageService>()
        val targets = mapOf("content" to target, "artifacts" to target)
        assertFailsWith<IllegalArgumentException> { service.inspect(inventory, retained, targets, " ") }
        assertFailsWith<IllegalArgumentException> { service.inspect(inventory, retained, emptyMap(), "new-instance") }
        for (objects in listOf(
            listOf(first.copy(key = "../outside")),
            listOf(first, first.copy(revision = "other")),
            listOf(first, second.copy(key = first.key)),
        )) {
            assertFailsWith<IllegalArgumentException> {
                service.inspect(inventory.copy(objects = objects), retained, targets, "new-instance")
            }
        }
        coVerify(exactly = 0) { retained.getInputStream(any()) }
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `selected inventory verifies required retained bytes on every inspection`(): Unit = runBlocking {
        service.inspect(inventory, backup, targets(), "new-instance")
        val target = delegate(content)
        backup.delete(StringObjectPath(second.payloadPath))
        assertFailsWith<ObjectNotFoundException> {
            service.inspect(inventory, backup, mapOf("content" to target, "artifacts" to target), "new-instance")
        }
        write(backup, second.payloadPath, "oops")
        assertFailsWith<BackupPayloadMismatchException> {
            service.inspect(inventory, backup, mapOf("content" to target, "artifacts" to target), "new-instance")
        }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `selected inventory propagates access errors and cancellation without progress writes`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.getInputStream(any()) } throws IOException("destination unavailable")
        assertFailsWith<IOException> {
            service.inspect(inventory, backup, mapOf("content" to content, "artifacts" to target), "new-instance")
        }
        coEvery { target.getInputStream(any()) } throws CancellationException("inspection cancelled")
        assertFailsWith<CancellationException> {
            service.inspect(inventory, backup, mapOf("content" to content, "artifacts" to target), "new-instance")
        }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
        assertMissing(content, first.key)
    }

    @Test
    fun `selected inventory restore rechecks targets after successful inspection`(): Unit = runBlocking {
        assertTrue(service.inspect(inventory, backup, targets(), "new-instance").objects.all {
            it.status == ObjectBackupRestoreObjectStatus.MISSING
        })
        write(artifacts, second.key, "new independent data")

        assertEquals(inventory, service.restore(inventory, backup, targets(), "new-instance"))

        val inspection = service.inspect(inventory, backup, targets(), "new-instance")
        assertTrue(inspection.objects.all { it.status == ObjectBackupRestoreObjectStatus.MATCHING })
        assertEquals("more", read(artifacts, second.key))
        assertEquals("data", read(content, first.key))
        assertTrue(checkpoints().all { it.status == ObjectBackupProgressStatus.COMPLETED })
    }

    private suspend fun inspect(
        contentTarget: ManagedObjectStorageService = content,
        artifactTarget: ManagedObjectStorageService = artifacts,
    ): ObjectBackupRestoreInspection = service.inspect(
        path, backup, mapOf("content" to contentTarget, "artifacts" to artifactTarget), "new-instance",
    )

    private fun targets(): Map<String, ManagedObjectStorageService> = mapOf("content" to content, "artifacts" to artifacts)

    private fun restorePayload(entry: BackupObject): BackupObject = entry.copy(payloadPath = entry.key)

    private suspend fun checkpoints(): List<ObjectBackupProgress> = withConnectionManager {
        progress.list(RESTORE, inventory.sourceId, "new-instance", inventory.scope, 0, 100)
    }

    private suspend fun save(manifest: ObjectBackupManifest = inventory) {
        ObjectBackupManifestStore(backup, Json).save(path, manifest)
    }

    private fun delegate(storage: ManagedObjectStorageService): ManagedObjectStorageService =
        mockk<ManagedObjectStorageService>().also { target ->
            coEvery { target.getInputStream(any()) } coAnswers { storage.getInputStream(firstArg()) }
            coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
                storage.setInputStream(firstArg(), secondArg(), thirdArg())
            }
        }

    private fun storage(name: String): ManagedFileSystemObjectStorageService = ManagedFileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    private suspend fun write(storage: ManagedObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }

    private suspend fun read(storage: ManagedObjectStorageService, key: String): String =
        storage.getInputStream(StringObjectPath(key)).use { it.readBytes().decodeToString() }

    private suspend fun assertMissing(storage: ManagedObjectStorageService, key: String) {
        assertFailsWith<ObjectNotFoundException> { storage.getInputStream(StringObjectPath(key)) }
    }

    private fun entry(store: String, key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject(store, key, "revision-1", bytes.size.toLong(), digest, "objects/$digest")
    }
}
