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
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
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
    private lateinit var backup: FileSystemObjectStorageService
    private lateinit var content: FileSystemObjectStorageService
    private lateinit var artifacts: FileSystemObjectStorageService
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
        val target = mockk<ObjectStorageService>()

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
            val target = mockk<ObjectStorageService>()

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
    fun `conflicting preexisting target data is rejected before writing other assets`(): Unit = runBlocking {
        write(artifacts, second.key, "independent data")

        val failure = assertFailsWith<IllegalStateException> { restore() }

        assertTrue(failure.message.orEmpty().contains(second.key))
        assertMissing(content, first.key)
        assertEquals("independent data", read(artifacts, second.key))
        assertTrue(checkpoints().all { it.attempts == 0 })
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
        val unavailable = mockk<ObjectStorageService>()
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

    private suspend fun restore(
        contentTarget: ObjectStorageService = content,
        artifactTarget: ObjectStorageService = artifacts,
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

    private fun delegate(storage: ObjectStorageService): ObjectStorageService =
        mockk<ObjectStorageService>().also { target ->
            coEvery { target.getInputStream(any()) } coAnswers { storage.getInputStream(firstArg()) }
            coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
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

    private fun entry(store: String, key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject(store, key, "revision-1", bytes.size.toLong(), digest, "objects/$digest")
    }
}
