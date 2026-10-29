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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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
class ObjectBackupRestoreIntegrationTest {
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
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_restore").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-restore-test",
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
    fun `restores original keys across logical stores without any live source`(): Unit = runBlocking {
        val events = BackupEventTestDispatcher()
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) { events }
        val target = delegate(content)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            val items = checkpoints()
            assertEquals(2, items.size)
            assertTrue(items.any { it.status == ObjectBackupProgressStatus.RUNNING })
            content.setInputStream(firstArg(), secondArg(), thirdArg())
        }

        assertEquals(inventory, restore(contentTarget = target))

        assertEquals("data", read(content, first.key))
        assertEquals("more", read(artifacts, second.key))
        assertTrue(checkpoints().all { it.status == ObjectBackupProgressStatus.COMPLETED })
        assertEquals(setOf(first.key, second.key), checkpoints().map { it.payload.payloadPath }.toSet())
        assertEquals(inventory, ObjectBackupManifestStore(backup, Json).load(path))
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `a missing later backup payload prevents every destination write`(): Unit = runBlocking {
        backup.delete(StringObjectPath(second.payloadPath))

        assertFailsWith<ObjectNotFoundException> { restore() }

        assertMissing(content, first.key)
        assertMissing(artifacts, second.key)
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `a corrupt later backup payload prevents every destination write`(): Unit = runBlocking {
        write(backup, second.payloadPath, "oops")

        assertFailsWith<BackupPayloadMismatchException> { restore() }

        assertMissing(content, first.key)
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `malformed manifest is rejected before accessing destinations`(): Unit = runBlocking {
        write(backup, path.toString(), "{")
        val target = mockk<ManagedObjectStorageService>()

        assertFailsWith<SerializationException> { restore(contentTarget = target) }

        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `missing store mapping is rejected before any destination write`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.restore(path, backup, mapOf("content" to content), "new-instance")
        }

        assertMissing(content, first.key)
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `noncanonical destination keys are rejected before any destination access`(): Unit = runBlocking {
        for (key in listOf("../outside", "/absolute", "folder/../outside", "folder//file", "folder/./file")) {
            save(inventory.copy(objects = listOf(first, second.copy(key = key))))
            val target = mockk<ManagedObjectStorageService>()

            assertFailsWith<IllegalArgumentException> { restore(contentTarget = target) }

            coVerify(exactly = 0) { target.getInputStream(any()) }
            coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        }
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `multiple revisions for the same target key require an unambiguous selection`(): Unit = runBlocking {
        save(inventory.copy(objects = listOf(first, first.copy(revision = "other-revision"))))

        assertFailsWith<IllegalArgumentException> { restore() }

        assertMissing(content, first.key)
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `explicit recovery replaces preexisting bytes at selected target keys`(): Unit = runBlocking {
        write(artifacts, second.key, "independent data")

        restore()

        assertEquals("data", read(content, first.key))
        assertEquals("more", read(artifacts, second.key))
        assertTrue(checkpoints().all { it.status == ObjectBackupProgressStatus.COMPLETED })
    }

    @Test
    fun `matching preexisting objects are verified and acknowledged without writes`(): Unit = runBlocking {
        write(content, first.key, "data")
        val target = delegate(content)
        coEvery { target.setInputStream(any(), any(), any()) } throws AssertionError("Matching objects must be reused")

        restore(contentTarget = target)

        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
        assertEquals(ObjectBackupProgressStatus.COMPLETED, checkpoints().single { it.payload.key == first.key }.status)
    }

    @Test
    fun `interrupted owned writes are repaired after service restart`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            artifacts.setInputStream(firstArg(), "mo".byteInputStream(), 2)
            throw IOException("destination disconnected")
        }

        assertFailsWith<IOException> { restore(artifactTarget = target) }

        assertEquals("data", read(content, first.key))
        assertEquals("mo", read(artifacts, second.key))
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoints().single { it.payload.key == second.key }.status)
        BackupProviderRegistrar().register()
        service = provide()
        val completedTarget = delegate(content)
        coEvery { completedTarget.setInputStream(any(), any(), any()) } throws AssertionError(
            "Completed objects must be reused",
        )

        restore(contentTarget = completedTarget)

        assertEquals("more", read(artifacts, second.key))
        assertEquals(2, checkpoints().single { it.payload.key == second.key }.attempts)
        coVerify(exactly = 0) { completedTarget.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `lost write acknowledgement reuses persisted output without rewriting`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            artifacts.setInputStream(firstArg(), secondArg(), thirdArg())
            throw IOException("acknowledgement lost")
        }

        assertFailsWith<IOException> { restore(artifactTarget = target) }

        val retryTarget = delegate(artifacts)
        coEvery { retryTarget.setInputStream(any(), any(), any()) } throws AssertionError(
            "Persisted output must be reused",
        )
        restore(artifactTarget = retryTarget)

        assertTrue(checkpoints().all { it.status == ObjectBackupProgressStatus.COMPLETED })
        coVerify(exactly = 0) { retryTarget.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `cancellation propagates and leaves failed progress available for retry`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            currentCoroutineContext().cancel(CancellationException("restore cancelled"))
            throw CancellationException("restore cancelled")
        }
        val attempt = async { restore(artifactTarget = target) }

        assertFailsWith<CancellationException> { attempt.await() }

        assertEquals(ObjectBackupProgressStatus.COMPLETED, checkpoints().single { it.payload.key == first.key }.status)
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoints().single { it.payload.key == second.key }.status)
        restore()
        assertEquals("more", read(artifacts, second.key))
    }

    @Test
    fun `destination storage failures do not get treated as missing objects`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.getInputStream(any()) } throws IOException("destination unavailable")

        assertFailsWith<IOException> { restore(artifactTarget = target) }

        assertMissing(content, first.key)
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `acknowledged destination corruption cannot complete a checkpoint`(): Unit = runBlocking {
        val target = delegate(artifacts)
        coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
            secondArg<java.io.InputStream>().readBytes()
            artifacts.setInputStream(firstArg(), "oops".byteInputStream(), 4)
            thirdArg<Long>()
        }

        assertFailsWith<BackupPayloadMismatchException> { restore(artifactTarget = target) }

        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoints().single { it.payload.key == second.key }.status)
    }

    @Test
    fun `distinct target keys can share a retained backup payload`(): Unit = runBlocking {
        val shared = first.copy(key = "metadata/c/1/content")
        save(inventory.copy(objects = listOf(first, shared)))

        restore()

        assertEquals("data", read(content, first.key))
        assertEquals("data", read(content, shared.key))
        assertEquals(2, checkpoints().size)
    }

    @Test
    fun `logical stores cannot map the same key to one destination namespace`(): Unit = runBlocking {
        save(inventory.copy(objects = listOf(first, second.copy(key = first.key))))

        assertFailsWith<IllegalArgumentException> {
            service.restore(path, backup, mapOf("content" to content, "artifacts" to content), "new-instance")
        }

        assertMissing(content, first.key)
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `the same key in independent logical stores restores different bytes`(): Unit = runBlocking {
        save(inventory.copy(objects = listOf(first, second.copy(key = first.key))))

        restore()

        assertEquals("data", read(content, first.key))
        assertEquals("more", read(artifacts, first.key))
        assertTrue(checkpoints().all { it.status == ObjectBackupProgressStatus.COMPLETED })
    }

    @Test
    fun `invalid destination identity and empty inventory have explicit outcomes`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { restore(destinationId = " ") }
        save(inventory.copy(objects = emptyList()))

        assertEquals(emptyList(), restore().objects)
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `selected inventory is validated and restored without reading a manifest path`(): Unit = runBlocking {
        val unavailable = mockk<ManagedObjectStorageService>()
        for (invalid in listOf(
            inventory.copy(version = 99),
            inventory.copy(objects = listOf(first, first)),
            inventory.copy(objects = listOf(first.copy(key = "../outside"))),
        )) {
            assertFailsWith<IllegalArgumentException> {
                service.restore(invalid, backup, mapOf("content" to unavailable), "new-instance")
            }
        }
        coVerify(exactly = 0) { unavailable.getInputStream(any()) }
        coVerify(exactly = 0) { unavailable.setInputStream(any(), any(), any()) }
        assertTrue(checkpoints().isEmpty())
        backup.delete(path)

        val restored = service.restore(
            inventory, backup, mapOf("content" to content, "artifacts" to artifacts), "new-instance",
        )

        assertEquals(inventory, restored)
        assertEquals("data", read(content, first.key))
        assertEquals("more", read(artifacts, second.key))
    }

    @Test
    fun `a later recovery replaces changed files while reusing unchanged files`(): Unit = runBlocking {
        restore()
        val changed = entry("content", first.key, "new data").copy(revision = "second-recovery", payloadPath = "assets/updated")
        write(backup, changed.payloadPath, "new data")
        val later = inventory.copy(id = "snapshot-2", objects = listOf(changed, second))
        save(later)
        write(content, "outside-selection", "keep this")
        val unchanged = delegate(artifacts)
        coEvery { unchanged.setInputStream(any(), any(), any()) } throws AssertionError("Unchanged files must be reused")

        assertEquals(later, restore(artifactTarget = unchanged))

        assertEquals("new data", read(content, first.key))
        assertEquals("more", read(artifacts, second.key))
        assertEquals("keep this", read(content, "outside-selection"))
        coVerify(exactly = 0) { unchanged.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `unchanged recovery does not download retained asset bodies again`(): Unit = runBlocking {
        restore()
        val retained = delegate(backup)
        coEvery { retained.getInputStream(any()) } coAnswers {
            val requested = firstArg<bosca.storage.service.ObjectPath>()
            check(requested.toString() == path.toString()) { "Matching assets must not be downloaded: $requested" }
            backup.getInputStream(requested)
        }

        assertEquals(inventory, service.restore(path, retained, mapOf("content" to content, "artifacts" to artifacts), "new-instance"))
    }

    @Test
    fun `changed recovery resumes after partial writes without rewriting completed files`(): Unit = runBlocking {
        restore()
        val changed = entry("content", first.key, "new data").copy(revision = "second-recovery", payloadPath = "assets/updated")
        val laterArtifact = entry("artifacts", second.key, "new package").copy(revision = "second-recovery", payloadPath = "assets/package")
        write(backup, changed.payloadPath, "new data")
        write(backup, laterArtifact.payloadPath, "new package")
        save(inventory.copy(id = "snapshot-2", objects = listOf(changed, laterArtifact)))
        val failed = delegate(artifacts)
        coEvery { failed.setInputStream(any(), any(), any()) } coAnswers {
            artifacts.setInputStream(firstArg(), "partial".byteInputStream(), 7)
            throw IOException("connection lost")
        }

        assertFailsWith<IOException> { restore(artifactTarget = failed) }
        assertEquals("new data", read(content, first.key))
        BackupProviderRegistrar().register()
        service = provide()
        val completed = delegate(content)
        coEvery { completed.setInputStream(any(), any(), any()) } throws AssertionError("Completed files must be reused")
        restore(contentTarget = completed)

        assertEquals("new package", read(artifacts, second.key))
        coVerify(exactly = 0) { completed.setInputStream(any(), any(), any()) }
    }

    private suspend fun restore(
        contentTarget: ManagedObjectStorageService = content,
        artifactTarget: ManagedObjectStorageService = artifacts,
        destinationId: String = "new-instance",
    ): ObjectBackupManifest = service.restore(
        path, backup, mapOf("content" to contentTarget, "artifacts" to artifactTarget), destinationId,
    )

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
