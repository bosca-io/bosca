package bosca.backup

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
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
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Generated job helpers/executors, real NATS JobRunner, PostgreSQL and application-selected stores. */
@OptIn(InternalDI::class)
class ObjectBackupRunQueueIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var nats: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var directory: Path
    private lateinit var queue: JobQueue
    private lateinit var runner: JobRunner
    private lateinit var runs: ObjectBackupRunService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private val first = entry("items/a", "data")
    private val second = entry("items/b", "more")
    private val inventory = ObjectBackupManifest(
        "queued-inventory", "source-instance", "content", "2026-10-17T00:00:00Z", listOf(first, second),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_queue").apply { start() }
        nats = SharedNatsContainer().apply { start() }
        natsPool = nats.newConnectionPool(2)
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 5),
            key = "object-backup-queue-test",
        ))
        directory = createTempDirectory("bosca-queued-backup-")
        configurations = listOf("source", "retained", "target").associateWith {
            StorageConfiguration("fs", "", basePath = directory.resolve(it).toString())
        }
        val catalog = JsonObject(configurations.mapValues {
            Json.encodeToJsonElement(StorageConfiguration.serializer(), it.value)
        })
        val root = JsonObject(mapOf("backup" to JsonObject(mapOf("objectStores" to catalog))))
        ProviderRegistry.clear()
        application = BoscaApplication(
            ApplicationConfig.load(Json.encodeToString(JsonObject.serializer(), root).byteInputStream()),
            ownsServices = false,
        )
        provides<BoscaApplication>(singleton = true) { application }
        provides<Json>(singleton = true) { application.json }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        val locks = NatsDistributedLockFactory(natsPool)
        val factory = NatsJobQueueFactory(
            natsPool, application.json, locks, enqueueEventChannel = null,
            enqueueCallbacks = emptyList(), processExpiredJobs = false,
        )
        val physicalQueue = "object-backup-" + UUID.random().toString().take(8)
        provides<JobQueueFactory>(singleton = true) {
            object : JobQueueFactory {
                override fun create(name: String): JobQueue {
                    assertEquals(BackupJobQueueNames.backupQueue, name)
                    return factory.create(physicalQueue)
                }
            }
        }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        runs = provide()
        queue = provide(name = BackupJobQueueNames.backupJobQueue)
        runner = JobRunner(queue, 2, locks)
        val stores = provide<ObjectBackupStorageService>()
        request { stores.bindConfigured(configurations.keys) }
        write("source", first.key, "data")
        write("source", second.key, "more")
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::runner.isInitialized) runner.shutdown()
            if (::nats.isInitialized) nats.stop()
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `real runner backs up and restores through selected stores`(): Unit = runBlocking {
        val backup = prepare("backup")
        val job = ObjectBackupRunJob(backup.key).enqueue()
        assertEquals(JsonObject(mapOf("key" to JsonPrimitive(backup.key))), job.getDefinition())
        runner.run()
        awaitDrained(job.getId())
        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(backup.key) }?.status)
        val restore = request {
            runs.prepareRestore(backup.inventory, "restored-instance", backup.path).also {
                runs.configureStorage(it.key, "retained", mapOf("content" to "target"))
            }
        }
        val restoreJob = ObjectBackupRunJob(restore.key).enqueue()
        awaitDrained(restoreJob.getId())

        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(restore.key) }?.status)
        assertEquals("data", read("target", first.key))
        assertEquals("more", read("target", second.key))
        assertEquals(1, request { runs.get(backup.key) }?.attempts)
        assertEquals(1, request { runs.get(restore.key) }?.attempts)
    }

    @Test
    fun `duplicate queued deliveries retain one completed run attempt`(): Unit = runBlocking {
        val run = prepare("backup")
        val firstJob = ObjectBackupRunJob(run.key).enqueue()
        val secondJob = ObjectBackupRunJob(run.key).enqueue()
        runner.run()
        awaitDrained(firstJob.getId(), secondJob.getId())
        val completed = assertNotNull(request { runs.get(run.key) })
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(1, completed.attempts)
        val repeated = ObjectBackupRunJob(run.key).enqueue()
        awaitDrained(repeated.getId())
        assertEquals(completed, request { runs.get(run.key) })
    }

    @Test
    fun `busy destination delays without consuming a run attempt or job failure`(): Unit = runBlocking {
        val holder = prepare("holder")
        val active = request { runs.start(holder.key, holder.version) }
        val waiting = prepare("waiting")
        val job = ObjectBackupRunJob(waiting.key).enqueue()
        runner.run()
        val delayed = awaitScheduled(job.getId())
        assertEquals(JobStatus.PENDING, delayed.status)
        assertEquals(0, delayed.failures)
        assertEquals(waiting, request { runs.get(waiting.key) })

        request { runs.fail(holder.key, active.version, "prior writer stopped") }
        awaitDrained(job.getId())

        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(waiting.key) }?.status)
        assertEquals(1, request { runs.get(waiting.key) }?.attempts)
    }

    @Test
    fun `cancelled queued history completes delivery without configuration or transfer`(): Unit = runBlocking {
        val cancelled = request {
            runs.prepare(inventory.copy(id = "cancelled"), "unconfigured-storage").let {
                runs.cancel(it.key, it.version, "operator cancelled")
            }
        }
        val job = ObjectBackupRunJob(cancelled.key).enqueue()
        runner.run()
        awaitDrained(job.getId())
        assertEquals(cancelled, request { runs.get(cancelled.key) })
        assertEquals(0, cancelled.attempts)
        assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
    }

    @Test
    fun `enqueue follows caller commit and rollback without inline storage execution`(): Unit = runBlocking {
        val run = prepare("backup")
        assertNull(queue.dequeue(25.milliseconds))
        var rolledBack: UUID? = null
        assertFailsWith<IOException> {
            request {
                transaction {
                    rolledBack = ObjectBackupRunJob(run.key).enqueue().getId()
                    assertNull(state(assertNotNull(rolledBack)))
                    throw IOException("abort dispatch")
                }
            }
        }
        assertNull(state(assertNotNull(rolledBack)))
        var committed: UUID? = null
        request {
            transaction {
                committed = ObjectBackupRunJob(run.key).enqueue().getId()
                assertNull(state(assertNotNull(committed)))
            }
        }
        assertNotNull(state(assertNotNull(committed)))
        assertEquals(run, request { runs.get(run.key) })
        assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
        runner.run()
        awaitDrained(assertNotNull(committed))
        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(run.key) }?.status)
    }

    private suspend fun prepare(id: String): ObjectBackupRun = request {
        runs.prepare(inventory.copy(id = id), "retained").also {
            runs.configureStorage(it.key, "retained", mapOf("content" to "source"))
        }
    }

    private suspend fun awaitDrained(vararg ids: UUID) {
        val state = natsPool.systemConnection().keyValue("job-state-" + queue.name)
        withTimeout(30_000) {
            while (ids.any { state.get(it.toString())?.valueAsString != null }) delay(25)
        }
    }

    private suspend fun awaitScheduled(id: UUID): SerializedJob {
        val connection = natsPool.systemConnection()
        val scheduled = connection.keyValue("job-scheduled-" + queue.name)
        val state = connection.keyValue("job-state-" + queue.name)
        withTimeout(15_000) {
            while (scheduled.get(id.toString())?.valueAsString?.toLongOrNull() == null) delay(25)
        }
        val value = assertNotNull(state.get(id.toString())?.valueAsString)
        return application.json.decodeFromString(SerializedJob.serializer(), value)
    }

    private suspend fun state(id: UUID): SerializedJob? {
        val state = natsPool.systemConnection().keyValue("job-state-" + queue.name)
        val value = state.get(id.toString())?.valueAsString ?: return null
        return application.json.decodeFromString(SerializedJob.serializer(), value)
    }

    private suspend fun write(store: String, key: String, value: String) {
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue(store)) {
            val bytes = value.toByteArray()
            it.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
        }
    }

    private suspend fun read(store: String, key: String): String =
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue(store)) {
            it.getString(StringObjectPath(key))
        }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private fun entry(key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject("content", key, "revision", bytes.size.toLong(), digest, "objects/" + digest)
    }
}
