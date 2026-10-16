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
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises complete inventories with real progress persistence and independently addressed stores. */
@OptIn(InternalDI::class)
class ObjectBackupInventoryTransferIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var progressService: ObjectBackupProgressService
    private lateinit var directory: Path
    private lateinit var content: FileSystemObjectStorageService
    private lateinit var media: FileSystemObjectStorageService
    private lateinit var destination: FileSystemObjectStorageService
    private val first = entry("content", "items/a", "a-1", "one")
    private val second = entry("media", "items/a", "b-1", "two")
    private val third = entry("content", "items/c", "c-1", "three")
    private val inventory = ObjectBackupManifest(
        id = "inventory-1",
        sourceId = "source-1",
        scope = "content",
        created = "2026-10-16T00:00:00Z",
        objects = listOf(first, second, third),
    )

    @BeforeTest
    fun setUp() = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_inventory_transfer").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 1),
            key = "object-inventory-transfer-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        progressService = provide()

        directory = createTempDirectory("bosca-inventory-transfer-")
        content = storage("content")
        media = storage("media")
        destination = storage("destination")
        write(content, first.key, "one")
        write(media, second.key, "two")
        write(content, third.key, "three")
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
    fun `copies the entire inventory using each declared source store`() = runBlocking {
        val result = copy()

        assertEquals(inventory.objects, result.map { it.payload })
        assertTrue(result.all { it.status == ObjectBackupProgressStatus.COMPLETED && it.attempts == 1 })
        assertEquals(listOf("one", "two", "three"), inventory.objects.map {
            destination.getString(StringObjectPath(it.payloadPath))
        })
        assertEquals(result.toSet(), persisted().values.toSet())
    }

    @Test
    fun `every item is prepared before storage opens without holding a database connection`() = runBlocking {
        val origin = mockk<ObjectStorageService>()
        var reads = 0
        coEvery { origin.getInputStream(any()) } coAnswers {
            assertEquals(0, pool.activeConnections)
            if (reads++ == 0) {
                val progress = persisted()
                assertEquals(3, progress.size)
                assertEquals(ObjectBackupProgressStatus.RUNNING, progress[first.revision]?.status)
                assertEquals(ObjectBackupProgressStatus.PENDING, progress[second.revision]?.status)
                assertEquals(ObjectBackupProgressStatus.PENDING, progress[third.revision]?.status)
            }
            content.getInputStream(firstArg())
        }

        copy(sources = sources(origin))

        assertEquals(2, reads)
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `failure leaves completed failed and pending items and retry resumes unfinished work`() = runBlocking {
        val unavailable = mockk<ObjectStorageService>()
        coEvery { unavailable.getInputStream(any()) } throws IOException("media unavailable")

        assertFailsWith<IOException> { copy(sources = sources(mediaSource = unavailable)) }

        val interrupted = persisted()
        assertEquals(ObjectBackupProgressStatus.COMPLETED, interrupted[first.revision]?.status)
        assertEquals(ObjectBackupProgressStatus.FAILED, interrupted[second.revision]?.status)
        assertEquals("media unavailable", interrupted[second.revision]?.error)
        assertEquals(ObjectBackupProgressStatus.PENDING, interrupted[third.revision]?.status)
        assertEquals(0, interrupted[third.revision]?.attempts)

        progressService = ObjectBackupProgressServiceImpl(provide())
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            assertEquals(third.key, firstArg<StringObjectPath>().toString())
            content.getInputStream(firstArg())
        }
        val writes = mutableListOf<String>()

        val resumed = copy(sources = sources(origin), target = recordingDestination(writes))

        assertTrue(resumed.all { it.status == ObjectBackupProgressStatus.COMPLETED })
        assertEquals(listOf(1, 2, 1), resumed.map { it.attempts })
        assertEquals(listOf(second.payloadPath, third.payloadPath), writes)
    }

    @Test
    fun `a later unchanged inventory reuses completed output without source reads or writes`() = runBlocking {
        val completed = copy()
        val contentOrigin = mockk<ObjectStorageService>()
        val mediaOrigin = mockk<ObjectStorageService>()
        val writes = mutableListOf<String>()

        val repeated = copy(
            inventory.copy(id = "inventory-2", created = "2026-10-16T01:00:00Z"),
            sources(contentOrigin, mediaOrigin),
            recordingDestination(writes),
        )

        assertEquals(completed, repeated)
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { contentOrigin.getInputStream(any()) }
        coVerify(exactly = 0) { mediaOrigin.getInputStream(any()) }
    }

    @Test
    fun `a later inventory copies new and changed revisions while retaining earlier progress`() = runBlocking {
        val completed = copy()
        val changed = entry(second.store, second.key, "b-2", "updated")
        val added = entry("content", "items/d", "d-1", "four")
        write(media, changed.key, "updated")
        write(content, added.key, "four")
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            assertEquals(added.key, firstArg<StringObjectPath>().toString())
            content.getInputStream(firstArg())
        }
        val writes = mutableListOf<String>()

        val result = copy(
            inventory.copy(id = "inventory-2", objects = listOf(first, changed, third, added)),
            sources(origin),
            recordingDestination(writes),
        )

        assertTrue(result.all { it.status == ObjectBackupProgressStatus.COMPLETED })
        assertEquals(listOf(changed.payloadPath, added.payloadPath), writes)
        val progress = persisted()
        assertEquals(5, progress.size)
        assertEquals(completed, completed.map { progress[it.payload.revision] })
        assertEquals("two", destination.getString(StringObjectPath(second.payloadPath)))
        assertEquals("updated", destination.getString(StringObjectPath(changed.payloadPath)))
    }

    @Test
    fun `cancellation propagates and leaves later inventory items pending`() = runBlocking {
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            currentCoroutineContext().cancel(CancellationException("inventory cancelled"))
            "two".byteInputStream()
        }
        val attempt = async { copy(sources = sources(mediaSource = origin)) }

        assertFailsWith<CancellationException> { attempt.await() }

        val progress = persisted()
        assertEquals(ObjectBackupProgressStatus.COMPLETED, progress[first.revision]?.status)
        assertEquals(ObjectBackupProgressStatus.FAILED, progress[second.revision]?.status)
        assertTrue(assertNotNull(progress[second.revision]?.error).contains("inventory cancelled"))
        assertEquals(ObjectBackupProgressStatus.PENDING, progress[third.revision]?.status)
        assertEquals(0, progress[third.revision]?.attempts)
    }

    @Test
    fun `invalid manifests and destination identities fail before progress or storage writes`() = runBlocking {
        val target = mockk<ObjectStorageService>()

        assertFailsWith<IllegalArgumentException> { copy(inventory.copy(version = 2), target = target) }
        assertFailsWith<IllegalArgumentException> {
            copy(inventory.copy(objects = listOf(first, first)), target = target)
        }
        assertFailsWith<IllegalArgumentException> {
            ObjectBackupTransfer(sources(), target).copy(inventory, " ", progressService)
        }

        assertTrue(persisted().isEmpty())
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `unknown source stores are rejected before any intent or bytes are written`() = runBlocking {
        val origin = mockk<ObjectStorageService>()
        val target = mockk<ObjectStorageService>()

        val failure = assertFailsWith<IllegalArgumentException> {
            copy(sources = mapOf("content" to origin), target = target)
        }

        assertTrue(failure.message.orEmpty().contains("media"))
        assertTrue(persisted().isEmpty())
        coVerify(exactly = 0) { origin.getInputStream(any()) }
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `conflicting persisted revision intent fails before transferring any item`() = runBlocking {
        withConnectionManager {
            progressService.prepare(
                BACKUP,
                inventory.sourceId, "destination-1", inventory.scope, second.copy(sha256 = "0".repeat(64)),
            )
        }
        val target = mockk<ObjectStorageService>()

        assertFailsWith<IllegalArgumentException> { copy(target = target) }

        assertTrue(persisted().values.all { it.status == ObjectBackupProgressStatus.PENDING })
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `shared payload references copy bytes once and complete each logical item`() = runBlocking {
        val shared = second.copy(length = first.length, sha256 = first.sha256, payloadPath = first.payloadPath)
        val origin = mockk<ObjectStorageService>()
        val writes = mutableListOf<String>()

        val result = copy(
            inventory.copy(objects = listOf(first, shared)),
            sources(mediaSource = origin),
            recordingDestination(writes),
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.status == ObjectBackupProgressStatus.COMPLETED })
        assertEquals(listOf(first.payloadPath), writes)
        coVerify(exactly = 0) { origin.getInputStream(any()) }
    }

    @Test
    fun `valid empty inventory completes without storage access or progress rows`() = runBlocking {
        val target = mockk<ObjectStorageService>()

        val result = copy(inventory.copy(objects = emptyList()), emptyMap(), target)

        assertTrue(result.isEmpty())
        assertTrue(persisted().isEmpty())
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `caller list changes during transfer cannot alter the captured inventory`() = runBlocking {
        val objects = inventory.objects.toMutableList()
        val origin = mockk<ObjectStorageService>()
        coEvery { origin.getInputStream(any()) } coAnswers {
            objects.clear()
            content.getInputStream(firstArg())
        }

        val result = copy(inventory.copy(objects = objects), sources(origin))

        assertTrue(objects.isEmpty())
        assertEquals(inventory.objects, result.map { it.payload })
        assertTrue(result.all { it.status == ObjectBackupProgressStatus.COMPLETED })
    }

    private suspend fun copy(
        manifest: ObjectBackupManifest = inventory,
        sources: Map<String, ObjectStorageService> = sources(),
        target: ObjectStorageService = destination,
    ): List<ObjectBackupProgress> = ObjectBackupTransfer(sources, target).copy(
        manifest, "destination-1", progressService,
    )

    private fun sources(
        contentSource: ObjectStorageService = content,
        mediaSource: ObjectStorageService = media,
    ): Map<String, ObjectStorageService> = mapOf("content" to contentSource, "media" to mediaSource)

    private suspend fun persisted(): Map<String, ObjectBackupProgress> = withConnectionManager {
        progressService.list(BACKUP, inventory.sourceId, "destination-1", inventory.scope, 0, 100)
            .associateBy { it.payload.revision }
    }

    private fun recordingDestination(writes: MutableList<String>): ObjectStorageService =
        mockk<ObjectStorageService>().also { target ->
            coEvery { target.getInputStream(any()) } coAnswers { destination.getInputStream(firstArg()) }
            coEvery { target.setInputStream(any(), any(), any()) } coAnswers {
                writes.add(firstArg<StringObjectPath>().toString())
                destination.setInputStream(firstArg(), secondArg(), thirdArg())
            }
        }

    private fun storage(name: String): FileSystemObjectStorageService = FileSystemObjectStorageService(
        "", "", mockk(), directory.resolve(name).toString(), mockk(),
    )

    private suspend fun write(storage: ObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }

    private fun entry(store: String, key: String, revision: String, data: String): BackupObject {
        val bytes = data.toByteArray()
        return BackupObject(
            store = store,
            key = key,
            revision = revision,
            length = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
            payloadPath = "objects/$revision",
        )
    }
}
