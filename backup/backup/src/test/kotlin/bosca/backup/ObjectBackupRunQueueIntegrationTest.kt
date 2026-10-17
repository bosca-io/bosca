package bosca.backup

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.afterCommit
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
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
    private var publicationFailure: Exception? = null
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
        publicationFailure = null
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
                    val delegate = factory.create(physicalQueue)
                    return object : JobQueue by delegate {
                        override suspend fun enqueueIfAbsent(job: Job): UUID {
                            // Simulates broker failure at the queue's real after-commit boundary.
                            afterCommit {
                                publicationFailure?.let { throw it }
                                delegate.enqueueIfAbsent(job)
                            }
                            return job.getId()
                        }
                    }
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
        val dispatch = request { provide<ObjectBackupDispatchService>().request(backup.key, backup.version) }
        runner.run()
        awaitDrained(dispatch.key)
        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(backup.key) }?.status)
        val restore = request {
            runs.prepareRestore(backup.inventory, "restored-instance", backup.path).also {
                runs.configureStorage(it.key, "retained", mapOf("content" to "target"))
            }
        }
        val restoreDispatch = request {
            provide<ObjectBackupDispatchService>().request(restore.key, restore.version)
        }
        awaitDrained(restoreDispatch.key)

        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(restore.key) }?.status)
        assertEquals("data", read("target", first.key))
        assertEquals("more", read("target", second.key))
        assertEquals(1, request { runs.get(backup.key) }?.attempts)
        assertEquals(1, request { runs.get(restore.key) }?.attempts)
    }

    @Test
    fun `duplicate queued deliveries retain one completed run attempt`(): Unit = runBlocking {
        val run = prepare("backup")
        val firstJob = ObjectBackupRunJob(run.key, run.version).enqueue()
        val secondJob = ObjectBackupRunJob(run.key, run.version).enqueue()
        runner.run()
        awaitDrained(firstJob.getId(), secondJob.getId())
        val completed = assertNotNull(request { runs.get(run.key) })
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(1, completed.attempts)
        val repeated = ObjectBackupRunJob(run.key, run.version).enqueue()
        awaitDrained(repeated.getId())
        assertEquals(completed, request { runs.get(run.key) })
    }

    @Test
    fun `busy destination delays without consuming a run attempt or job failure`(): Unit = runBlocking {
        val holder = prepare("holder")
        val active = request { runs.start(holder.key, holder.version) }
        val waiting = prepare("waiting")
        val dispatch = request { provide<ObjectBackupDispatchService>().request(waiting.key, waiting.version) }
        runner.run()
        val delayed = awaitScheduled(dispatch.key)
        assertEquals(JobStatus.PENDING, delayed.status)
        assertEquals(0, delayed.failures)
        assertEquals(waiting, request { runs.get(waiting.key) })

        request { runs.fail(holder.key, active.version, "prior writer stopped") }
        awaitDrained(dispatch.key)

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
        val job = ObjectBackupRunJob(cancelled.key, cancelled.version).enqueue()
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
                    rolledBack = ObjectBackupRunJob(run.key, run.version).enqueue().getId()
                    assertNull(state(assertNotNull(rolledBack)))
                    throw IOException("abort dispatch")
                }
            }
        }
        assertNull(state(assertNotNull(rolledBack)))
        var committed: UUID? = null
        request {
            transaction {
                committed = ObjectBackupRunJob(run.key, run.version).enqueue().getId()
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

    @Test
    fun `concurrent dispatch requests reuse one durable queue identity`(): Unit = runBlocking {
        val run = prepare("dispatch")
        val ids = (1..6).map {
            async { request { provide<ObjectBackupDispatchService>().request(run.key, run.version) } }
        }.awaitAll()
        assertEquals(1, ids.map { it.key }.toSet().size)
        val dispatch = ids.first()
        assertEquals(run.key, dispatch.runKey)
        assertEquals(run.version, dispatch.version)
        assertEquals(dispatch, request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
        val queued = assertNotNull(state(dispatch.key))
        assertEquals(JsonObject(mapOf(
            "key" to JsonPrimitive(run.key), "version" to JsonPrimitive(run.version),
        )), queued.definition)
        assertEquals(run, request { runs.get(run.key) })
        runner.run()
        awaitDrained(dispatch.key)
        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(run.key) }?.status)
        assertEquals(1, request { runs.get(run.key) }?.attempts)
    }

    @Test
    fun `dispatch request and delivery follow the caller transaction`(): Unit = runBlocking {
        val run = prepare("dispatch-transaction")
        assertNull(queue.dequeue(25.milliseconds))
        var rolledBack: ObjectBackupDispatch? = null
        assertFailsWith<IOException> {
            request {
                transaction {
                    rolledBack = provide<ObjectBackupDispatchService>().request(run.key, run.version)
                    assertNull(state(assertNotNull(rolledBack).key))
                    throw IOException("rollback request")
                }
            }
        }
        assertNull(request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
        assertNull(state(assertNotNull(rolledBack).key))
        val committed = request {
            transaction {
                provide<ObjectBackupDispatchService>().request(run.key, run.version).also {
                    assertNotNull(provide<ObjectBackupDispatchService>().get(run.key, run.version))
                    assertNull(state(it.key))
                }
            }
        }
        assertNotNull(state(committed.key))
        assertEquals(run, request { runs.get(run.key) })
        assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
    }

    @Test
    fun `publication failure after commit is recovered with the same ID after provider restart`(): Unit = runBlocking {
        val run = prepare("publication-failure")
        assertNull(queue.dequeue(25.milliseconds))
        publicationFailure = IOException("broker publication unavailable")
        request { provide<ObjectBackupDispatchService>().request(run.key, run.version) }
        val saved = assertNotNull(request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
        assertNull(state(saved.key))
        assertEquals(run, request { runs.get(run.key) })
        BackupProviderRegistrar().register()
        publicationFailure = null
        assertEquals(1, provide<ObjectBackupDispatchService>().recover())
        assertNotNull(state(saved.key))
        assertEquals(saved, request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
        runner.run()
        awaitDrained(saved.key)
        assertEquals(ObjectBackupRunStatus.COMPLETED, request { runs.get(run.key) }?.status)
        assertEquals(0, provide<ObjectBackupDispatchService>().recover())
    }

    @Test
    fun `recovery publication failure propagates without removing durable requests`(): Unit = runBlocking {
        val run = prepare("recovery-failure")
        val saved = request { provide<ObjectBackupDispatchService>().request(run.key, run.version) }
        val originalState = assertNotNull(rawState(saved.key))
        publicationFailure = IOException("broker publication unavailable")
        assertFailsWith<IOException> { provide<ObjectBackupDispatchService>().recover() }
        assertEquals(saved, request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
        assertEquals(originalState, rawState(saved.key))
        publicationFailure = null
        assertEquals(1, provide<ObjectBackupDispatchService>().recover())
        assertEquals(originalState, rawState(saved.key))
    }

    @Test
    fun `recovery pages only exact pending requested versions without replacing queue state`(): Unit = runBlocking {
        val pending = (1..3).map { prepare("page-$it") }
        val dispatches = pending.map { run ->
            request { provide<ObjectBackupDispatchService>().request(run.key, run.version) }
        }
        val cancelled = prepare("cancelled-dispatch")
        request {
            provide<ObjectBackupDispatchService>().request(cancelled.key, cancelled.version)
            runs.cancel(cancelled.key, cancelled.version, "cancelled before worker")
        }
        val running = prepare("running-dispatch")
        request {
            provide<ObjectBackupDispatchService>().request(running.key, running.version)
            runs.start(running.key, running.version)
        }
        val snapshots = dispatches.map { assertNotNull(rawState(it.key)) }
        assertEquals(2, provide<ObjectBackupDispatchService>().recover(0, 2))
        assertEquals(1, provide<ObjectBackupDispatchService>().recover(2, 2))
        assertEquals(0, provide<ObjectBackupDispatchService>().recover(3, 2))
        assertEquals(snapshots, dispatches.map { rawState(it.key) })
        assertEquals(0, pending.sumOf { request { runs.get(it.key) }?.attempts ?: -1 })
    }

    @Test
    fun `dispatch requires current pending version and configured storage`(): Unit = runBlocking {
        val unmapped = request { runs.prepare(inventory.copy(id = "unmapped"), "retained") }
        assertFailsWith<IllegalStateException> {
            request { provide<ObjectBackupDispatchService>().request(unmapped.key, unmapped.version) }
        }
        val run = prepare("invalid-state")
        assertFailsWith<IllegalStateException> {
            request { provide<ObjectBackupDispatchService>().request(run.key, run.version + 1) }
        }
        val active = request { runs.start(run.key, run.version) }
        assertFailsWith<IllegalStateException> {
            request { provide<ObjectBackupDispatchService>().request(active.key, active.version) }
        }
        val failed = request { runs.fail(active.key, active.version, "stopped before copy") }
        assertFailsWith<IllegalStateException> {
            request { provide<ObjectBackupDispatchService>().request(failed.key, failed.version) }
        }
        val cancelled = request {
            runs.prepare(inventory.copy(id = "cancelled-unmapped"), "retained").let {
                runs.cancel(it.key, it.version, "cancelled")
            }
        }
        assertFailsWith<IllegalStateException> {
            request { provide<ObjectBackupDispatchService>().request(cancelled.key, cancelled.version) }
        }
        assertEquals(0, provide<ObjectBackupDispatchService>().recover())
        assertNull(request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
    }

    @Test
    fun `dispatch input validation and recovery transaction guard precede publication`(): Unit = runBlocking {
        val service = provide<ObjectBackupDispatchService>()
        assertFailsWith<IllegalArgumentException> { service.request(" ", 0) }
        assertFailsWith<IllegalArgumentException> { service.request("missing", -1) }
        assertFailsWith<IllegalArgumentException> { service.get(" ", 0) }
        assertFailsWith<IllegalArgumentException> { service.get("missing", -1) }
        assertFailsWith<IllegalArgumentException> { service.recover(-1, 10) }
        assertFailsWith<IllegalArgumentException> { service.recover(0, 0) }
        assertFailsWith<IllegalStateException> {
            request { transaction { service.recover() } }
        }
        assertFailsWith<IllegalStateException> { request { service.request("missing", 0) } }
    }

    @Test
    fun `old delivery cannot start the new pending retry version`(): Unit = runBlocking {
        val old = prepare("stale-cancel")
        val retry = request {
            val cancelled = runs.cancel(old.key, old.version, "cancel old request")
            runs.retry(cancelled.key, cancelled.version)
        }
        val stale = ObjectBackupRunJob(old.key, old.version).enqueue()
        runner.run()
        awaitDrained(stale.getId())
        assertEquals(retry, request { runs.get(old.key) })
        assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
    }

    @Test
    fun `old delivery cannot silently retry a recorded failed attempt`(): Unit = runBlocking {
        val old = prepare("stale-failure")
        val failed = request {
            val active = runs.start(old.key, old.version)
            runs.fail(active.key, active.version, "stopped failed worker")
        }
        val stale = ObjectBackupRunJob(old.key, old.version).enqueue()
        runner.run()
        awaitDrained(stale.getId())
        assertEquals(failed, request { runs.get(old.key) })
    }

    @Test
    fun `explicit retry has a new durable dispatch identity`(): Unit = runBlocking {
        val original = prepare("explicit-dispatch-retry")
        val old = request { provide<ObjectBackupDispatchService>().request(original.key, original.version) }
        val pending = request {
            val cancelled = runs.cancel(original.key, original.version, "replace request")
            runs.retry(cancelled.key, cancelled.version)
        }
        val next = request { provide<ObjectBackupDispatchService>().request(pending.key, pending.version) }
        assertTrue(next.key != old.key)
        assertEquals(pending.version, next.version)
        assertEquals(1, provide<ObjectBackupDispatchService>().recover())
        runner.run()
        awaitDrained(old.key, next.key)
        val completed = assertNotNull(request { runs.get(original.key) })
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(1, completed.attempts)
        assertFailsWith<IllegalStateException> {
            request { provide<ObjectBackupDispatchService>().request(completed.key, completed.version) }
        }
    }

    @Test
    fun `recovery cancellation propagates and retains the request`(): Unit = runBlocking {
        val run = prepare("recovery-cancel")
        val saved = request { provide<ObjectBackupDispatchService>().request(run.key, run.version) }
        val originalState = rawState(saved.key)
        publicationFailure = CancellationException("recovery stopped")
        assertFailsWith<CancellationException> { provide<ObjectBackupDispatchService>().recover() }
        assertEquals(saved, request { provide<ObjectBackupDispatchService>().get(run.key, run.version) })
        assertEquals(originalState, rawState(saved.key))
    }

    @Test
    fun `partial retry dispatch reuses verified retained objects`(): Unit = runBlocking {
        val run = prepare("partial-dispatch")
        val old = request { provide<ObjectBackupDispatchService>().request(run.key, run.version) }
        directory.resolve("source").resolve(second.key).toFile().delete()
        assertFailsWith<ObjectNotFoundException> { runs.executeConfigured(run.key) }
        val partial = assertNotNull(request { runs.get(run.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(1L, partial.result?.completed)
        assertTrue(directory.resolve("source").resolve(first.key).toFile().delete())
        write("source", second.key, "more")
        val retry = request { runs.retry(run.key, partial.version) }
        val next = request { provide<ObjectBackupDispatchService>().request(run.key, retry.version) }
        runner.run()
        awaitDrained(old.key, next.key)
        val completed = assertNotNull(request { runs.get(run.key) })
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        assertEquals("data", read("retained", first.payloadPath))
        assertEquals("more", read("retained", second.payloadPath))
    }

    @Test
    fun `version is checked again when configuration resolution races cancellation and retry`(): Unit = runBlocking {
        val run = prepare("configuration-race")
        val resolving = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val delegate = provide<ObjectBackupStorageService>()
        val gated = object : ObjectBackupStorageService by delegate {
            override suspend fun configurations(keys: Set<String>): Map<String, StorageConfiguration> {
                resolving.complete(Unit)
                resume.await()
                return delegate.configurations(keys)
            }
        }
        val service = ObjectBackupRunServiceImpl(provide(), provide(), provide(), application.json, gated)
        val execution = async { service.executeConfigured(run.key, run.version) }
        try {
            withTimeout(10_000) { resolving.await() }
            val retry = request {
                val cancelled = runs.cancel(run.key, run.version, "cancel during configuration lookup")
                runs.retry(run.key, cancelled.version)
            }
            resume.complete(Unit)
            assertEquals(retry, execution.await())
            assertEquals(retry, request { runs.get(run.key) })
            assertTrue(directory.resolve("retained").toFile().listFiles().isNullOrEmpty())
        } finally {
            execution.cancel()
            resume.complete(Unit)
        }
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

    private suspend fun rawState(id: UUID): String? =
        natsPool.systemConnection().keyValue("job-state-" + queue.name).get(id.toString())?.valueAsString

    private suspend fun state(id: UUID): SerializedJob? = rawState(id)?.let {
        application.json.decodeFromString(SerializedJob.serializer(), it)
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
