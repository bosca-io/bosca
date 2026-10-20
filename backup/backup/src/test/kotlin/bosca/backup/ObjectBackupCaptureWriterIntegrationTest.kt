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
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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

/** Shared capture/run ownership through PostgreSQL, generated DI and operation-scoped storage. */
@OptIn(InternalDI::class)
class ObjectBackupCaptureWriterIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var directory: Path
    private lateinit var service: ObjectBackupCaptureService
    private lateinit var runs: ObjectBackupRunService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private lateinit var observedFactory: ObjectStorageServiceFactory
    private val opened = CopyOnWriteArrayList<String>()
    private val closed = CopyOnWriteArrayList<String>()
    private var onOpen: suspend (String) -> Unit = {}
    private var onRead: suspend () -> Unit = {}
    private var onWrite: suspend (String) -> Unit = {}
    private var onClose: (String) -> Unit = {}
    private val selection = ObjectBackupCaptureInventory(
        "selected", "source-instance", "content", "2026-10-18T00:00:00Z",
        mapOf("content" to listOf(ObjectStorageEntry("asset", ObjectStorageInfo(4, version = "v1")))),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("capture_writers").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4),
            key = "capture-writers-test",
        ))
        directory = createTempDirectory("bosca-capture-writers-")
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()), ownsServices = false)
        ProviderRegistry.clear()
        provides<BoscaApplication>(singleton = true) { application }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { backupTestJson }
        StorageProviderRegistrar().register()
        val factory = provide<ObjectStorageServiceFactory>()
        observedFactory = object : ObjectStorageServiceFactory by factory {
            override suspend fun create(configuration: StorageConfiguration): ObjectStorageService {
                val id = Path.of(checkNotNull(configuration.basePath)).fileName.toString()
                onOpen(id)
                val actual = factory.create(configuration)
                opened.add(id)
                return object : ObjectStorageService by actual {
                    override suspend fun getInputStream(path: ObjectPath, version: String): InputStream {
                        check(id == "source" && path.toString() == "asset" && version == "v1")
                        onRead()
                        return "data".byteInputStream()
                    }

                    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                        onWrite(path.toString())
                        return actual.setInputStream(path, stream, length)
                    }

                    override fun close() {
                        closed.add(id)
                        actual.close()
                        onClose(id)
                    }
                }
            }

            override suspend fun <T> withStorage(
                configuration: StorageConfiguration,
                block: suspend (ObjectStorageService) -> T,
            ): T = create(configuration).use { block(it) }
        }
        restart()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        configurations = listOf("source", "retained", "independent").associateWith {
            StorageConfiguration("fs", "", basePath = directory.resolve(it).toString())
        }
        withConnectionManager {
            val storage = provide<ObjectBackupStorageService>()
            for ((key, value) in configurations) storage.bind(key, value)
        }
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
    fun `claim remains held from first open through last close without holding a database connection`(): Unit = runBlocking {
        val plan = configured()
        assertNull(withConnectionManager { service.getWriter(plan.key) })
        val tokens = CopyOnWriteArrayList<String>()
        fun observe() {
            assertEquals(0, pool.activeConnections)
            val writer = assertNotNull(writer(plan.key))
            assertEquals(plan.key, writer.planKey)
            assertEquals("retained", writer.storageKey)
            tokens.add(writer.key)
        }
        onOpen = { observe() }
        onRead = { observe() }
        onWrite = { observe() }
        onClose = { observe() }
        assertEquals(1, capture(plan).objects.size)
        assertTrue(tokens.isNotEmpty())
        assertEquals(1, tokens.distinct().size)
        assertEquals(opened.sorted(), closed.sorted())
        assertNull(withConnectionManager { service.getWriter(plan.key) })
    }

    @Test
    fun `repeating successful execution gets a fresh token and reuses completed source revisions`(): Unit = runBlocking {
        val plan = configured()
        val tokens = CopyOnWriteArrayList<String>()
        val reads = AtomicInteger()
        onOpen = { if (it == "retained") tokens.add(assertNotNull(writer(plan.key)).key) }
        onRead = { reads.incrementAndGet() }
        val first = capture(plan)
        val second = capture(plan)
        assertEquals(first, second)
        assertEquals(1, reads.get())
        assertEquals(2, tokens.size)
        assertNotEquals(tokens[0], tokens[1])
        assertNull(writer(plan.key))
    }

    @Test
    fun `same-plan and different-plan contenders fail before opening storage`(): Unit = runBlocking {
        val plan = configured()
        val other = configured(selection.copy(id = "other"))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        onRead = { entered.complete(Unit); finish.await() }
        val active = async { capture(plan) }
        try {
            withTimeout(10_000) { entered.await() }
            val before = opened.toList()
            val owner = assertNotNull(withConnectionManager { service.getWriter(plan.key) })
            for (contender in listOf(plan, other)) {
                val failure = assertFailsWith<ObjectBackupDestinationBusyException> {
                    withTimeout(3_000) { capture(contender) }
                }
                assertEquals("retained", failure.storageKey)
                assertEquals(before, opened.toList())
                assertEquals(owner, withConnectionManager { service.getWriter(plan.key) })
            }
        } finally {
            finish.complete(Unit)
            active.cancelAndJoin()
        }
        assertNull(writer(plan.key))
    }

    @Test
    fun `independent retained destinations capture concurrently`(): Unit = runBlocking {
        val plans = listOf(configured(), configured(destination = "independent"))
        val both = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        onRead = { if (reads.incrementAndGet() == 2) both.complete(Unit); finish.await() }
        val active = plans.map { async { capture(it) } }
        try {
            withTimeout(10_000) { both.await() }
            val writers = plans.map { assertNotNull(writer(it.key)) }
            assertEquals(setOf("retained", "independent"), writers.map { it.storageKey }.toSet())
            finish.complete(Unit)
            assertEquals(2, active.awaitAll().size)
        } finally {
            finish.complete(Unit)
            active.forEach { it.cancelAndJoin() }
        }
        plans.forEach { assertNull(writer(it.key)) }
    }

    @Test
    fun `backup and restore writers block capture without changing their attempts`(): Unit = runBlocking {
        val plan = configured()
        for (restore in listOf(false, true)) {
            val run = prepareRun("held-$restore", restore)
            val running = withConnectionManager { runs.start(run.key, run.version) }
            val failure = assertFailsWith<ObjectBackupDestinationBusyException> { capture(plan) }
            assertEquals("retained", failure.storageKey)
            assertTrue(opened.isEmpty())
            assertEquals(running, withConnectionManager { runs.get(run.key) })
            withConnectionManager { runs.fail(run.key, running.version, "writer stopped") }
        }
        assertEquals(1, capture(plan).objects.size)
    }

    @Test
    fun `capture blocks backup and restore start and rolls back a losing run transition`(): Unit = runBlocking {
        val plan = configured()
        val waiting = listOf(prepareRun("backup", false), prepareRun("restore", true))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        onRead = { entered.complete(Unit); finish.await() }
        val active = async { capture(plan) }
        try {
            withTimeout(10_000) { entered.await() }
            for (run in waiting) {
                assertFailsWith<ObjectBackupDestinationBusyException> {
                    withConnectionManager { runs.start(run.key, run.version) }
                }
                assertEquals(run, withConnectionManager { runs.get(run.key) })
            }
        } finally {
            finish.complete(Unit)
            active.cancelAndJoin()
        }
        for (run in waiting) {
            val running = withConnectionManager { runs.start(run.key, run.version) }
            withConnectionManager { runs.fail(run.key, running.version, "writer stopped") }
        }
    }

    @Test
    fun `restart preserves ownership until observed stopped-writer recovery`(): Unit = runBlocking {
        val plan = configured()
        val stopped = seedWriter(plan, "stopped-token")
        restart()
        assertEquals(stopped, withConnectionManager { service.getWriter(plan.key) })
        assertFailsWith<ObjectBackupDestinationBusyException> { capture(plan) }
        assertTrue(opened.isEmpty())
        withConnectionManager { service.releaseWriter(plan.key, stopped.key) }
        assertNull(writer(plan.key))
        assertEquals(1, capture(plan).objects.size)
    }

    @Test
    fun `stale and unrelated recovery tokens leave replacement ownership intact`(): Unit = runBlocking {
        val plan = configured()
        val other = configured(selection.copy(id = "other"))
        val stopped = seedWriter(plan, "stopped-token")
        withConnectionManager { service.releaseWriter(plan.key, stopped.key) }
        val replacement = seedWriter(plan, "replacement-token")
        for ((key, token) in listOf(plan.key to stopped.key, other.key to replacement.key, plan.key to "unknown")) {
            assertFailsWith<IllegalStateException> { withConnectionManager { service.releaseWriter(key, token) } }
            assertEquals(replacement, withConnectionManager { service.getWriter(plan.key) })
        }
    }

    @Test
    fun `stopped-writer recovery participates in caller rollback`(): Unit = runBlocking {
        val plan = configured()
        val stopped = seedWriter(plan, "stopped-token")
        assertFailsWith<IOException> {
            withConnectionManager { transaction { service.releaseWriter(plan.key, stopped.key); throw IOException("rollback") } }
        }
        assertEquals(stopped, withConnectionManager { service.getWriter(plan.key) })
        withConnectionManager { service.releaseWriter(plan.key, stopped.key) }
        assertNull(writer(plan.key))
    }

    @Test
    fun `missing and blank recovery inputs fail without touching storage`(): Unit = runBlocking {
        assertNull(withConnectionManager { service.getWriter("missing") })
        assertFailsWith<IllegalStateException> { withConnectionManager { service.releaseWriter("missing", "token") } }
        assertFailsWith<IllegalArgumentException> { withConnectionManager { service.getWriter(" ") } }
        for ((key, token) in listOf(" " to "token", "missing" to " ")) {
            assertFailsWith<IllegalArgumentException> { withConnectionManager { service.releaseWriter(key, token) } }
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `open read payload publication and close failures release after closing opened stores`(): Unit = runBlocking {
        for (phase in listOf("open", "read", "payload", "publication", "close")) {
            val plan = configured(selection.copy(id = phase, sourceId = "source-$phase"))
            val failure = IOException("failed $phase")
            onOpen = { if (phase == "open" && it == "source") throw failure }
            onRead = { if (phase == "read") throw failure }
            onWrite = { path ->
                val selectedWrite = if (phase == "publication") path.endsWith(".json") else !path.endsWith(".json")
                if (phase in setOf("payload", "publication") && selectedWrite) throw failure
            }
            onClose = {
                assertNotNull(writer(plan.key))
                if (phase == "close" && it == "source") throw failure
            }
            assertOriginal(failure, assertFailsWith<IOException> { capture(plan) })
            assertEquals(opened.sorted(), closed.sorted())
            assertNull(writer(plan.key))
            onOpen = {}
            onRead = {}
            onWrite = {}
            onClose = {}
            assertEquals(1, capture(plan).objects.size)
            opened.clear()
            closed.clear()
        }
    }

    @Test
    fun `cancellation closes stores before releasing its claim and permits retry`(): Unit = runBlocking {
        val plan = configured()
        val ownersAtClose = CopyOnWriteArrayList<ObjectBackupCaptureWriter?>()
        onRead = { currentCoroutineContext().cancel(CancellationException("capture stopped")) }
        onClose = { ownersAtClose.add(writer(plan.key)) }
        supervisorScope {
            val active = async { capture(plan) }
            assertFailsWith<CancellationException> { active.await() }
            active.join()
        }
        assertEquals(opened.sorted(), closed.sorted())
        assertEquals(opened.size, ownersAtClose.size)
        assertTrue(ownersAtClose.all { it != null })
        assertNull(writer(plan.key))
        onRead = {}
        onClose = {}
        assertEquals(1, capture(plan).objects.size)
    }

    @Test
    fun `release failure is loud after successful capture and leaves ownership recoverable`(): Unit = runBlocking {
        val plan = configured()
        val failure = IOException("release failed")
        restart(failure)
        assertOriginal(failure, assertFailsWith<IOException> { capture(plan) })
        assertEquals(opened.sorted(), closed.sorted())
        val stopped = assertNotNull(writer(plan.key))
        restart()
        withConnectionManager { service.releaseWriter(plan.key, stopped.key) }
        assertEquals(1, capture(plan).objects.size)
    }

    @Test
    fun `failed release is suppressed on the original storage failure`(): Unit = runBlocking {
        val plan = configured()
        val failure = IOException("read failed")
        val releaseFailure = IOException("release failed")
        onRead = { throw failure }
        restart(releaseFailure)
        val actual = assertFailsWith<IOException> { capture(plan) }
        assertOriginal(failure, actual)
        assertTrue(actual.suppressed.any { containsFailure(it, releaseFailure) })
        assertNotNull(writer(plan.key))
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `failed release preserves cancellation and its durable claim`(): Unit = runBlocking {
        val plan = configured()
        val releaseFailure = IOException("release failed")
        onRead = { throw CancellationException("capture stopped") }
        restart(releaseFailure)
        val failure = assertFailsWith<CancellationException> { capture(plan) }
        assertTrue(failure.suppressed.any { containsFailure(it, releaseFailure) })
        assertNotNull(writer(plan.key))
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `cancellation during claim acquisition releases committed ownership before opening storage`(): Unit = runBlocking {
        val plan = configured()
        supervisorScope {
            lateinit var active: Deferred<ObjectBackupManifest>
            restart(afterClaim = { active.cancel(CancellationException("stopped during claim")) })
            active = async(start = CoroutineStart.LAZY) { capture(plan) }
            active.start()
            assertFailsWith<CancellationException> { active.await() }
            active.join()
        }
        assertNull(writer(plan.key))
        assertTrue(opened.isEmpty())
        restart()
        assertEquals(1, capture(plan).objects.size)
    }

    @Test
    fun `claim transaction failure rolls ownership back before storage opens`(): Unit = runBlocking {
        val plan = configured()
        val failure = IOException("claim transaction failed")
        restart(afterClaim = { throw failure })
        assertOriginal(failure, assertFailsWith<IOException> { capture(plan) })
        assertNull(writer(plan.key))
        assertTrue(opened.isEmpty())
        restart()
        assertEquals(1, capture(plan).objects.size)
    }

    private suspend fun configured(
        inventory: ObjectBackupCaptureInventory = selection,
        destination: String = "retained",
    ): ObjectBackupCapturePlan = withConnectionManager {
        service.prepareInventory(inventory, destination, "payloads", "manifests/${inventory.id}.json").also {
            service.configureStorage(it.key, mapOf("content" to "source"))
        }
    }

    private suspend fun capture(plan: ObjectBackupCapturePlan) = service.captureConfigured(plan.key, configurations, null)

    private fun containsFailure(actual: Throwable, expected: Throwable): Boolean =
        generateSequence(actual) { it.cause }.any { it === expected }

    private fun assertOriginal(expected: Throwable, actual: Throwable) {
        assertEquals(expected.message, actual.message)
        assertTrue(containsFailure(actual, expected), "Original failure must survive coroutine stack recovery")
    }

    private suspend fun prepareRun(id: String, restore: Boolean): ObjectBackupRun = withConnectionManager {
        val entry = BackupObject("content", "asset", "v1", 4,
            MessageDigest.getInstance("SHA-256").digest("data".toByteArray()).joinToString("") { "%02x".format(it) },
            "payloads/retained-data")
        val inventory = ObjectBackupManifest(id, "run-source", "run-scope", "2026-10-18T00:00:00Z", listOf(entry))
        if (restore) {
            runs.prepareRestore(inventory, "restore-destination", "manifests/$id.json").also {
                runs.configureStorage(it.key, "independent", mapOf("content" to "retained"))
            }
        } else {
            runs.prepare(inventory, "retained").also {
                runs.configureStorage(it.key, "retained", mapOf("content" to "source"))
            }
        }
    }

    private suspend fun restart(releaseFailure: IOException? = null, afterClaim: (() -> Unit)? = null) {
        ProviderRegistry.clear()
        provides<BoscaApplication>(singleton = true) { application }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { backupTestJson }
        StorageProviderRegistrar().register()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) { observedFactory }
        BackupProviderRegistrar().register()
        if (releaseFailure != null || afterClaim != null) {
            val actual = provide<ObjectBackupCaptureRepository>()
            provides<ObjectBackupCaptureRepository>(singleton = true, overrideExisting = true) {
                object : ObjectBackupCaptureRepository by actual {
                    override suspend fun claimWriter(key: String, storageKey: String, token: String): Int {
                        val claimed = actual.claimWriter(key, storageKey, token)
                        afterClaim?.invoke()
                        return claimed
                    }

                    override suspend fun releaseWriter(key: String, expectedKey: String): Int {
                        if (releaseFailure != null) throw releaseFailure
                        return actual.releaseWriter(key, expectedKey)
                    }
                }
            }
        }
        service = provide()
        runs = provide()
    }

    private fun seedWriter(plan: ObjectBackupCapturePlan, token: String): ObjectBackupCaptureWriter {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("insert into backup.object_store_writers (storage_key, capture_key, capture_token) values (?, ?, ?)").use {
                it.setString(1, plan.destinationId)
                it.setString(2, plan.key)
                it.setString(3, token)
                assertEquals(1, it.executeUpdate())
            }
        }
        return ObjectBackupCaptureWriter(token, plan.key, plan.destinationId)
    }

    private fun writer(key: String): ObjectBackupCaptureWriter? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("select capture_token, capture_key, storage_key from backup.object_store_writers where capture_key = ?").use {
                it.setString(1, key)
                it.executeQuery().use { rows ->
                    if (rows.next()) ObjectBackupCaptureWriter(rows.getString(1), rows.getString(2), rows.getString(3)) else null
                }
            }
        }
}
