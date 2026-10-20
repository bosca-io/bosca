package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fixed provider selections through generated capture/restore services and real progress/storage. */
@OptIn(InternalDI::class)
class ObjectBackupCaptureInventoryIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var target: ObjectStorageService
    private lateinit var service: ObjectBackupCaptureService
    private lateinit var sources: Map<String, ObjectStorageService>
    private val requests = mutableListOf<String>()
    private val writes = mutableListOf<String>()
    private val data = mutableMapOf("artifacts/a@v1" to "aaaa", "content/b@v1" to "bbbb")
    private var failSource: String? = null
    private var cancelSource = false
    private var failPublication = false
    private var onSourceRead: (suspend () -> Unit)? = null
    private val first = ObjectStorageEntry("a", ObjectStorageInfo(4, version = "v1"))
    private val second = ObjectStorageEntry("b", ObjectStorageInfo(4, version = "v1"))
    private val selected = ObjectBackupCaptureInventory(
        "selected", "source", "content", "2026-10-18T00:00:00Z",
        linkedMapOf("content" to listOf(second), "artifacts" to listOf(first)),
    )
    private val outputPath = "manifests/selected.json"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_capture_inventory").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-capture-inventory-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        directory = createTempDirectory("bosca-capture-inventory-")
        val filesystem = filesystem("retained")
        target = object : ObjectStorageService by filesystem {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                assertEquals(0, pool.activeConnections)
                return filesystem.getObjectInfo(path)
            }

            override suspend fun getInputStream(path: ObjectPath): InputStream {
                assertEquals(0, pool.activeConnections)
                return filesystem.getInputStream(path)
            }

            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                assertEquals(0, pool.activeConnections)
                writes.add(path.toString())
                if (failPublication && path.toString() == outputPath) throw IOException("publication failed")
                return filesystem.setInputStream(path, stream, length)
            }
        }
        sources = listOf("content", "artifacts").associateWith { store ->
            mockk<ObjectStorageService>().also { source ->
                coEvery { source.getInputStream(any(), any<String>()) } coAnswers {
                    assertEquals(0, pool.activeConnections)
                    val key = "$store/${firstArg<ObjectPath>()}@${secondArg<String>()}"
                    requests.add(key)
                    onSourceRead?.also { onSourceRead = null }?.invoke()
                    if (key == failSource) {
                        if (cancelSource) currentCoroutineContext().cancel(CancellationException("capture cancelled"))
                        else throw IOException("selected revision unavailable")
                    }
                    (data[key] ?: throw IOException("source offline")).byteInputStream()
                }
            }
        }
        service = provide()
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::target.isInitialized) target.close()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `captures every selected store in deterministic order and publishes a portable manifest`(): Unit = runBlocking {
        val result = capture()
        assertEquals(listOf("artifacts/a@v1", "content/b@v1"), requests)
        assertEquals(listOf("artifacts", "content"), result.objects.map { it.store })
        assertEquals(selected.id, result.id)
        assertEquals(selected.created, result.created)
        assertEquals(2, result.objects.map { it.payloadPath }.toSet().size)
        assertTrue(result.objects.all { it.payloadPath.startsWith("payloads/") })
        assertEquals(result, ObjectBackupManifestStore(target, Json).load(StringObjectPath(outputPath)))
        assertEquals(2, checkpoints().size)
        for (source in sources.values) coVerify(exactly = 0) { source.close() }
    }

    @Test
    fun `restarted unchanged capture uses recorded paths without source reads`(): Unit = runBlocking {
        val first = capture()
        val before = checkpoints()
        requests.clear()
        writes.clear()
        data.clear()
        BackupProviderRegistrar().register()
        service = provide()
        val repeated = capture(prefix = "fresh-prefix")
        assertEquals(first, repeated)
        assertTrue(requests.isEmpty())
        assertEquals(listOf(outputPath), writes)
        assertEquals(before, checkpoints())
    }

    @Test
    fun `a later selected inventory transfers only new revisions and retains older payloads`(): Unit = runBlocking {
        val previous = capture()
        requests.clear()
        data["artifacts/a@v2"] = "newer"
        val changed = selected.copy(id = "later", stores = mapOf(
            "content" to listOf(second), "artifacts" to listOf(first.copy(info = ObjectStorageInfo(5, version = "v2"))),
        ))
        val result = capture(changed, path = "manifests/later.json")
        assertEquals(listOf("artifacts/a@v2"), requests)
        assertEquals(previous.objects.last().payloadPath, result.objects.last().payloadPath)
        assertEquals(3, checkpoints().size)
        assertEquals("aaaa", target.getString(StringObjectPath(previous.objects.first().payloadPath)))
    }

    @Test
    fun `a different capture cannot replace an earlier manifest at the same path`(): Unit = runBlocking {
        val previous = capture()
        requests.clear()
        writes.clear()
        assertFailsWith<IllegalArgumentException> {
            capture(selected.copy(id = "different", stores = emptyMap()))
        }
        assertEquals(previous, ObjectBackupManifestStore(target, Json).load(StringObjectPath(outputPath)))
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `an empty capture cannot publish over an unrelated retained payload`(): Unit = runBlocking {
        val previous = capture()
        val payload = previous.objects.first()
        requests.clear()
        writes.clear()
        assertFailsWith<IllegalArgumentException> {
            capture(selected.copy(id = "empty", stores = emptyMap()), path = payload.payloadPath)
        }
        assertEquals("aaaa", target.getString(StringObjectPath(payload.payloadPath)))
        assertEquals(previous, ObjectBackupManifestStore(target, Json).load(StringObjectPath(outputPath)))
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `partial capture failure preserves completed entries and retry only reads unfinished revisions`(): Unit = runBlocking {
        failSource = "content/b@v1"
        assertFailsWith<IOException> { capture() }
        assertEquals(listOf("artifacts/a@v1", "content/b@v1"), requests)
        assertEquals(1, checkpoints().size)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, checkpoints().single().status)
        assertNotPublished()
        failSource = null
        requests.clear()
        val result = capture()
        assertEquals(listOf("content/b@v1"), requests)
        assertEquals(2, result.objects.size)
        assertEquals(result, ObjectBackupManifestStore(target, Json).load(StringObjectPath(outputPath)))
    }

    @Test
    fun `cancellation preserves successful captures and prevents publication`(): Unit = runBlocking {
        failSource = "content/b@v1"
        cancelSource = true
        val work = async { capture() }
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        assertEquals(1, checkpoints().size)
        assertNotPublished()
        failSource = null
        requests.clear()
        assertEquals(2, capture().objects.size)
        assertEquals(listOf("content/b@v1"), requests)
    }

    @Test
    fun `publication failure retries using successful captured checkpoints without source access`(): Unit = runBlocking {
        failPublication = true
        assertFailsWith<IOException> { capture() }
        val before = checkpoints()
        assertEquals(2, before.size)
        assertNotPublished()
        failPublication = false
        requests.clear()
        data.clear()
        val result = capture()
        assertTrue(requests.isEmpty())
        assertEquals(before, checkpoints())
        assertEquals(result, ObjectBackupManifestStore(target, Json).load(StringObjectPath(outputPath)))
    }

    @Test
    fun `published capture restores both stores while every source is offline`(): Unit = runBlocking {
        val result = capture()
        data.clear()
        requests.clear()
        val destinations = mapOf("content" to filesystem("restore-content"), "artifacts" to filesystem("restore-artifacts"))
        try {
            val restored = provide<ObjectBackupRestoreService>().restore(
                StringObjectPath(outputPath), target, destinations, "recovery",
            )
            assertEquals(result, restored)
            assertEquals("aaaa", destinations.getValue("artifacts").getString(StringObjectPath("a")))
            assertEquals("bbbb", destinations.getValue("content").getString(StringObjectPath("b")))
            assertTrue(requests.isEmpty())
        } finally {
            for (storage in destinations.values) storage.close()
        }
    }

    @Test
    fun `invalid selected versions are rejected before any store is accessed`(): Unit = runBlocking {
        val invalid = selected.copy(stores = selected.stores + ("content" to listOf(second.copy(info = ObjectStorageInfo(4)))))
        assertFailsWith<IllegalArgumentException> { capture(invalid) }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `missing source mapping fails before earlier valid selections are captured`(): Unit = runBlocking {
        sources = sources - "content"
        assertFailsWith<IllegalArgumentException> { capture() }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `source destination alias is rejected before any selected capture`(): Unit = runBlocking {
        sources = sources + ("content" to target)
        assertFailsWith<IllegalArgumentException> { capture() }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `invalid identity prefix and manifest paths fail before I O`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { capture(destination = " ") }
        assertFailsWith<IllegalArgumentException> { capture(selected.copy(created = "yesterday")) }
        for (path in listOf("", "/absolute", "a/../b", "a//b")) {
            assertFailsWith<IllegalArgumentException> { capture(prefix = path) }
            assertFailsWith<IllegalArgumentException> { capture(path = path) }
        }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `active caller transaction is rejected even for an empty inventory`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> {
            withConnectionManager {
                transaction { capture(selected.copy(stores = emptyMap())) }
            }
        }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `known active checkpoint prevents all inventory storage I O`(): Unit = runBlocking {
        seedKnown(ObjectBackupProgressStatus.RUNNING)
        assertFailsWith<IllegalStateException> { capture() }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `known payload path cannot become this inventory manifest output`(): Unit = runBlocking {
        seedKnown(ObjectBackupProgressStatus.COMPLETED, outputPath)
        assertFailsWith<IllegalArgumentException> { capture() }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `mutable caller collections are isolated before the first source read`(): Unit = runBlocking {
        val entries = mutableListOf(first)
        val stores = selected.stores.toMutableMap().apply { put("artifacts", entries) }
        val supplied = selected.copy(stores = stores)
        val suppliedSources = sources.toMutableMap()
        onSourceRead = {
            entries.clear()
            stores.clear()
            suppliedSources.clear()
        }
        sources = suppliedSources
        assertEquals(2, capture(supplied).objects.size)
        assertEquals(listOf("artifacts/a@v1", "content/b@v1"), requests)
    }

    @Test
    fun `empty selected inventory publishes its explicit scope without source reads`(): Unit = runBlocking {
        sources = emptyMap()
        val result = capture(selected.copy(stores = emptyMap()))
        assertTrue(result.objects.isEmpty())
        assertEquals(listOf(outputPath), writes)
        assertEquals(result, ObjectBackupManifestStore(target, Json).load(StringObjectPath(outputPath)))
        assertTrue(checkpoints().isEmpty())
    }

    @Test
    fun `failed first checkpoint commit retries with a fresh path and preserves unreferenced output`(): Unit = runBlocking {
        val original = provide<ObjectBackupProgressService>()
        service = ObjectBackupCaptureServiceImpl(object : ObjectBackupProgressService by original {
            override suspend fun completeVerified(
                key: String,
                expectedVersion: Long,
                objectVersion: String?,
            ): ObjectBackupProgress = throw IOException("checkpoint unavailable")
        }, Json, provide(), mockk())
        assertFailsWith<IOException> { capture() }
        assertTrue(checkpoints().isEmpty())
        val orphan = writes.single()
        assertNotNull(target.getObjectInfo(StringObjectPath(orphan)))
        service = provide()
        val result = capture()
        assertEquals(2, result.objects.size)
        assertTrue(result.objects.none { it.payloadPath == orphan })
        assertNotNull(target.getObjectInfo(StringObjectPath(orphan)))
    }

    @Test
    fun `missing retained entry is repaired while other selected revisions reuse their checkpoints`(): Unit = runBlocking {
        val previous = capture()
        target.delete(StringObjectPath(previous.objects.first().payloadPath))
        requests.clear()
        val repaired = capture()
        assertEquals(previous, repaired)
        assertEquals(listOf("artifacts/a@v1"), requests)
        assertEquals(2, checkpoints().single { it.payload.store == "artifacts" }.attempts)
    }

    @Test
    fun `multiple retained versions of one key remain distinct selected manifest entries`(): Unit = runBlocking {
        data["artifacts/a@v2"] = "newer"
        val historical = selected.copy(stores = mapOf("artifacts" to listOf(
            first.copy(info = ObjectStorageInfo(5, version = "v2")), first,
        )))
        val result = capture(historical)
        assertEquals(listOf("v1", "v2"), result.objects.map { it.revision })
        assertEquals(listOf("artifacts/a@v1", "artifacts/a@v2"), requests)
        assertEquals(2, result.objects.map { it.payloadPath }.toSet().size)
    }

    @Test
    fun `prepared plan survives restart and executes using only its saved key`(): Unit = runBlocking {
        val plan = preparePlan()
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
        assertTrue(checkpoints().isEmpty())
        BackupProviderRegistrar().register()
        service = provide()
        val result = executePlan(plan.key)
        assertEquals(2, result.objects.size)
        assertEquals(listOf("artifacts/a@v1", "content/b@v1"), requests)
        assertEquals(result, ObjectBackupManifestStore(target, Json).load(StringObjectPath(plan.path)))
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
    }

    @Test
    fun `prepared plan resumes partial capture in another service without changing its selection`(): Unit = runBlocking {
        val plan = preparePlan()
        failSource = "content/b@v1"
        assertFailsWith<IOException> { executePlan(plan.key) }
        assertEquals(1, checkpoints().size)
        assertNotPublished()
        failSource = null
        requests.clear()
        BackupProviderRegistrar().register()
        service = provide()
        assertEquals(2, executePlan(plan.key).objects.size)
        assertEquals(listOf("content/b@v1"), requests)
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
    }

    @Test
    fun `prepared plan retains input through cancellation and successful retry`(): Unit = runBlocking {
        val plan = preparePlan()
        failSource = "content/b@v1"
        cancelSource = true
        val work = async { executePlan(plan.key) }
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
        assertEquals(1, checkpoints().size)
        assertNotPublished()
        failSource = null
        requests.clear()
        assertEquals(2, executePlan(plan.key).objects.size)
        assertEquals(listOf("content/b@v1"), requests)
    }

    @Test
    fun `prepared plan retries publication without rereading either source`(): Unit = runBlocking {
        val plan = preparePlan()
        failPublication = true
        assertFailsWith<IOException> { executePlan(plan.key) }
        val before = checkpoints()
        assertEquals(2, before.size)
        failPublication = false
        requests.clear()
        data.clear()
        assertEquals(2, executePlan(plan.key).objects.size)
        assertTrue(requests.isEmpty())
        assertEquals(before, checkpoints())
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
    }

    @Test
    fun `missing or blank plan keys fail before storage access`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { executePlan("unknown") }
        assertFailsWith<IllegalArgumentException> { executePlan(" ") }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `plan execution rejects an active caller transaction before storage I O`(): Unit = runBlocking {
        val plan = preparePlan()
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { executePlan(plan.key) } }
        }
        assertTrue(requests.isEmpty())
        assertTrue(writes.isEmpty())
    }

    private suspend fun preparePlan() = withConnectionManager {
        service.prepareInventory(selected, "retained", "payloads", outputPath)
    }

    private suspend fun executePlan(key: String) = service.captureInventory(key, sources, target, OffsetDateTime.MIN)

    private suspend fun capture(
        inventory: ObjectBackupCaptureInventory = selected,
        destination: String = "retained",
        prefix: String = "payloads",
        path: String = outputPath,
    ) = service.captureInventory(inventory, destination, sources, target, prefix, path, OffsetDateTime.MIN)

    private suspend fun checkpoints() = withConnectionManager {
        provide<ObjectBackupProgressService>().list(ObjectBackupOperation.BACKUP, "source", "retained", "content", 0, 100)
    }

    private suspend fun assertNotPublished() {
        assertFailsWith<ObjectNotFoundException> { target.getObjectInfo(StringObjectPath(outputPath)) }
    }

    private suspend fun seedKnown(status: ObjectBackupProgressStatus, path: String = "payloads/known") = withConnectionManager {
        val progress = provide<ObjectBackupProgressService>()
        val digest = MessageDigest.getInstance("SHA-256").digest("bbbb".toByteArray()).joinToString("") { "%02x".format(it) }
        val pending = progress.prepare(ObjectBackupOperation.BACKUP, "source", "retained", "content",
            BackupObject("content", "b", "v1", 4, digest, path))
        val running = progress.start(pending.key, pending.version)
        if (status == ObjectBackupProgressStatus.COMPLETED) progress.complete(running.key, running.version)
    }

    private suspend fun filesystem(name: String) = provide<ObjectStorageServiceFactory>().create(
        StorageConfiguration("fs", "", basePath = directory.resolve(name).toString()),
    )
}
