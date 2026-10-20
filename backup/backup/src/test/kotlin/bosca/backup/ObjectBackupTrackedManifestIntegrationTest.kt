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
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Real progress repositories and independent filesystem publication through generated providers. */
@OptIn(InternalDI::class)
class ObjectBackupTrackedManifestIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var storage: ObjectStorageService
    private lateinit var progress: ObjectBackupProgressService
    private var reads = 0
    private var metadata = 0
    private var writes = 0
    private val bytes = "data".toByteArray()
    private val payload = BackupObject(
        "content", "asset", "source-v1", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "payloads/asset",
    )
    private val inventory = ObjectBackupManifest("capture", "source", "content", "2026-10-17T00:00:00Z", listOf(payload))
    private val path = StringObjectPath("manifests/capture.json")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_tracked_manifest").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-tracked-manifest-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        directory = createTempDirectory("bosca-tracked-manifest-")
        val filesystem = provide<ObjectStorageServiceFactory>().create(
            StorageConfiguration("fs", "", basePath = directory.toString()),
        )
        storage = object : ObjectStorageService by filesystem {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                assertEquals(0, pool.activeConnections)
                metadata++
                return filesystem.getObjectInfo(path)
            }

            override suspend fun getInputStream(path: ObjectPath): InputStream {
                assertEquals(0, pool.activeConnections)
                reads++
                return filesystem.getInputStream(path)
            }

            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                assertEquals(0, pool.activeConnections)
                writes++
                return filesystem.setInputStream(path, stream, length)
            }
        }
        progress = provide()
        storage.setInputStream(StringObjectPath(payload.payloadPath), bytes.inputStream(), bytes.size.toLong())
        writes = 0
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::storage.isInitialized) storage.close()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `publication requires a completed checkpoint before reading assets or writing output`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { publish() }
        assertEquals(0, metadata)
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `pending running and failed evidence cannot publish an inventory`(): Unit = runBlocking {
        val pending = prepare()
        for (status in listOf(ObjectBackupProgressStatus.PENDING, ObjectBackupProgressStatus.RUNNING, ObjectBackupProgressStatus.FAILED)) {
            val observed = when (status) {
                ObjectBackupProgressStatus.PENDING -> pending
                ObjectBackupProgressStatus.RUNNING -> withConnectionManager { progress.start(pending.key, pending.version) }
                else -> withConnectionManager {
                    val running = assertNotNull(progress.get(pending.key))
                    progress.fail(running.key, running.version, "stopped")
                }
            }
            assertFailsWith<IllegalStateException> { publish() }
            assertEquals(observed, withConnectionManager { progress.get(pending.key) })
        }
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `restore checkpoints cannot authorize backup publication`(): Unit = runBlocking {
        complete(prepare(ObjectBackupOperation.RESTORE))
        assertFailsWith<IllegalStateException> { publish() }
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `source destination and scope are part of publication evidence identity`(): Unit = runBlocking {
        complete(prepare())
        for ((selected, destination) in listOf(
            inventory.copy(sourceId = "other") to "retained",
            inventory.copy(scope = "other") to "retained",
            inventory to "other",
        )) {
            assertFailsWith<IllegalStateException> { publish(selected, destination) }
        }
        assertEquals(0, metadata)
        assertEquals(0, writes)
    }

    @Test
    fun `conflicting digest length and retained path cannot reuse completed intent`(): Unit = runBlocking {
        complete(prepare())
        for (changed in listOf(
            payload.copy(sha256 = "b".repeat(64)), payload.copy(length = 3), payload.copy(payloadPath = "payloads/other"),
        )) {
            assertFailsWith<IllegalArgumentException> { publish(inventory.copy(objects = listOf(changed))) }
        }
        assertEquals(0, metadata)
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `all revision checkpoints are required even when they share a retained payload`(): Unit = runBlocking {
        complete(prepare())
        val selected = inventory.copy(objects = listOf(payload, payload.copy(store = "artifacts", key = "other")))
        assertFailsWith<IllegalStateException> { publish(selected) }
        assertEquals(0, metadata)
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `filesystem publication always verifies payload bytes and returns a portable manifest`(): Unit = runBlocking {
        val completed = complete(prepare())
        publish()
        assertEquals(2, reads)
        assertEquals(1, metadata)
        assertEquals(1, writes)
        assertEquals(completed, withConnectionManager { progress.get(completed.key) })
        assertEquals(inventory, ObjectBackupManifestStore(storage, Json).load(path))
    }

    @Test
    fun `manifest ownership is independent of JSON formatting settings`(): Unit = runBlocking {
        complete(prepare())
        publish()
        val manifests = ObjectBackupManifestStore(storage, Json { prettyPrint = true })
        manifests.saveTracked(path, inventory, "retained", progress, OffsetDateTime.MIN)
        assertEquals(inventory, manifests.load(path))
    }

    @Test
    fun `invalid inventory destination and output paths fail before asset I O`(): Unit = runBlocking {
        complete(prepare())
        for (invalid in listOf(inventory.copy(id = " "), inventory.copy(created = "yesterday"),
            inventory.copy(objects = listOf(payload, payload)))) {
            assertFailsWith<IllegalArgumentException> { publish(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { publish(destination = " ") }
        for (invalidPath in listOf("../manifest.json", payload.payloadPath)) {
            assertFailsWith<IllegalArgumentException> {
                ObjectBackupManifestStore(storage, Json).saveTracked(
                    StringObjectPath(invalidPath), inventory, "retained", progress, null,
                )
            }
        }
        assertEquals(0, metadata)
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test
    fun `active caller transaction is rejected before storage I O`(): Unit = runBlocking {
        complete(prepare())
        val unobserved = provide<ObjectStorageServiceFactory>().create(StorageConfiguration("fs", "", basePath = directory.toString()))
        try {
            assertFailsWith<IllegalStateException> {
                withConnectionManager {
                    transaction {
                        ObjectBackupManifestStore(unobserved, Json).saveTracked(path, inventory, "retained", progress, null)
                    }
                }
            }
        } finally {
            unobserved.close()
        }
        assertFailsWith<ObjectNotFoundException> { storage.getObjectInfo(path) }
    }

    @Test
    fun `empty captured inventory publishes without invented checkpoints`(): Unit = runBlocking {
        val selected = inventory.copy(objects = emptyList())
        publish(selected)
        assertEquals(0, metadata)
        assertEquals(1, reads)
        assertEquals(1, writes)
        assertEquals(selected, ObjectBackupManifestStore(storage, Json).load(path))
    }

    private suspend fun prepare(operation: ObjectBackupOperation = ObjectBackupOperation.BACKUP) = withConnectionManager {
        progress.prepare(operation, "source", "retained", "content", payload)
    }

    private suspend fun complete(prepared: ObjectBackupProgress) = withConnectionManager {
        val running = progress.start(prepared.key, prepared.version)
        progress.complete(running.key, running.version)
    }

    private suspend fun publish(selected: ObjectBackupManifest = inventory, destination: String = "retained") =
        ObjectBackupManifestStore(storage, Json).saveTracked(path, selected, destination, progress, OffsetDateTime.MIN)
}
