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
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real run/configuration persistence and multi-store backup/restore through generated providers. */
@OptIn(InternalDI::class)
class ObjectBackupConfiguredRunIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var runs: ObjectBackupRunService
    private lateinit var storage: ObjectBackupStorageService
    private lateinit var factory: ObjectStorageServiceFactory
    private lateinit var configurations: Map<String, StorageConfiguration>
    private val mapping = mapOf("content" to "source-content", "artifacts" to "source-artifacts")
    private val targets = mapOf("content" to "target-content", "artifacts" to "target-artifacts")
    private val first = entry("content", "content")
    private val second = entry("artifacts", "artifact")
    private val inventory = ObjectBackupManifest(
        "configured-inventory", "original-instance", "content", "2026-10-16T00:00:00Z", listOf(first, second),
    )
    private val manifestPath = "manifests/input.json"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_configured_runs").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-configured-run-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        runs = provide()
        storage = provide()
        factory = provide()
        directory = createTempDirectory("bosca-configured-run-")
        configurations = (mapping.values + targets.values + "retained").associateWith { key ->
            StorageConfiguration("fs", "", basePath = directory.resolve(key).toString())
        }
        request { for ((key, configuration) in configurations) storage.bind(key, configuration) }
        write("source-content", first.key, "content")
        write("source-artifacts", second.key, "artifact")
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
    fun `storage mapping survives restart and snapshots mutable caller input`(): Unit = runBlocking {
        val run = prepare()
        assertNull(request { runs.getStorage(run.key) })
        val input = mapping.toMutableMap()
        val plan = request { runs.configureStorage(run.key, "retained", input) }
        input["content"] = "target-content"
        BackupProviderRegistrar().register()
        runs = provide()
        assertEquals(run.key, plan.key)
        assertEquals("retained", plan.backupKey)
        assertEquals(mapping, plan.stores)
        assertEquals(plan, request { runs.getStorage(run.key) })
        assertEquals(plan, request { runs.configureStorage(run.key, "retained", mapping) })
        assertEquals(run, request { runs.get(run.key) })
        assertFailsWith<IllegalArgumentException> { request { runs.configureStorage(run.key, "retained", input) } }
        assertEquals(plan, request { runs.getStorage(run.key) })
    }

    @Test
    fun `invalid mappings cannot leave partial configuration`(): Unit = runBlocking {
        val run = prepare()
        val invalid = listOf(
            mapping - "artifacts",
            mapping + ("unexpected" to "target-content"),
            mapping + ("content" to "missing"),
            mapping + ("content" to "retained"),
        )
        for (selected in invalid) {
            assertFailsWith<IllegalArgumentException> { request { runs.configureStorage(run.key, "retained", selected) } }
            assertNull(request { runs.getStorage(run.key) })
        }
        assertFailsWith<IllegalArgumentException> { request { runs.configureStorage(run.key, "target-content", mapping) } }
        assertNull(request { runs.getStorage(run.key) })
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `configuration rolls back with the caller transaction`(): Unit = runBlocking {
        val run = prepare()
        assertFailsWith<IOException> {
            request {
                transaction {
                    runs.configureStorage(run.key, "retained", mapping)
                    throw IOException("abort configuration")
                }
            }
        }
        assertNull(request { runs.getStorage(run.key) })
        assertNotNull(request { runs.configureStorage(run.key, "retained", mapping) })
    }

    @Test
    fun `concurrent different mappings cannot configure one run twice`(): Unit = runBlocking {
        val run = prepare()
        val alternatives = listOf(mapping, mapping + ("content" to "target-content"))
        val results = alternatives.map { selected ->
            async {
                try {
                    request { runs.configureStorage(run.key, "retained", selected) }
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(results.filterNotNull().single(), request { runs.getStorage(run.key) })
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `an attempted run cannot acquire a new unverified mapping`(): Unit = runBlocking {
        val run = prepare()
        val running = request { runs.start(run.key, run.version) }
        assertFailsWith<IllegalStateException> { request { runs.configureStorage(run.key, "retained", mapping) } }
        val failed = request { runs.fail(run.key, running.version, "writer stopped") }
        request { runs.retry(run.key, failed.version) }
        assertFailsWith<IllegalStateException> { request { runs.configureStorage(run.key, "retained", mapping) } }
        assertNull(request { runs.getStorage(run.key) })
    }

    @Test
    fun `configured backup opens exact source namespaces and publishes verified output`(): Unit = runBlocking {
        val run = configureBackup()
        val completed = runs.executeConfigured(run.key, configurations + ("unused" to StorageConfiguration("invalid", "")))
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(1, completed.attempts)
        assertEquals(2L, completed.result?.completed)
        factory.withStorage(configurations.getValue("retained")) {
            val manifests = ObjectBackupManifestStore(it, Json)
            assertEquals(inventory, manifests.load(StringObjectPath(run.path)))
            manifests.verify(inventory)
        }
        assertEquals("content", read("source-content", first.key))
        assertEquals("artifact", read("source-artifacts", second.key))
        assertNull(request { storage.get("unused") })
        assertEquals(mapping, request { runs.getStorage(run.key) }?.stores)
    }

    @Test
    fun `configured restore applies original keys into independently mapped namespaces`(): Unit = runBlocking {
        val run = configureRestore()
        val completed = runs.executeConfigured(run.key, configurations)
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals("content", read("target-content", first.key))
        assertEquals("artifact", read("target-artifacts", second.key))
        assertEquals("content", read("retained", first.payloadPath))
        assertEquals("artifact", read("retained", second.payloadPath))
        assertEquals(targets, request { runs.getStorage(run.key) }?.stores)
    }

    @Test
    fun `retry of a partial configured backup reuses verified payloads without the earlier source`(): Unit = runBlocking {
        val run = configureBackup()
        directory.resolve("source-artifacts/${second.key}").toFile().delete()
        assertFailsWith<Exception> { runs.executeConfigured(run.key, configurations) }
        val partial = assertNotNull(request { runs.get(run.key) })
        assertEquals(ObjectBackupRunStatus.PARTIAL, partial.status)
        assertEquals(1L, partial.result?.completed)
        directory.resolve("source-content/${first.key}").toFile().delete()
        write("source-artifacts", second.key, "artifact")

        val completed = runs.executeConfigured(run.key, configurations)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        assertEquals(2L, completed.result?.completed)
    }

    @Test
    fun `missing configurations do not start an attempt or open stores`(): Unit = runBlocking {
        val run = configureBackup()
        assertFailsWith<IllegalArgumentException> { runs.executeConfigured(run.key, configurations - "source-artifacts") }
        assertEquals(run, request { runs.get(run.key) })
        assertEquals(false, directory.resolve("retained").toFile().exists())
    }

    @Test
    fun `changed storage configuration fails before transfer and preserves bound addresses`(): Unit = runBlocking {
        val run = configureBackup()
        val before = request { storage.get("retained") }
        val changed = configurations + ("retained" to StorageConfiguration(
            "fs", "", basePath = directory.resolve("different").toString(),
        ))
        assertFailsWith<IllegalArgumentException> { runs.executeConfigured(run.key, changed) }
        assertEquals(ObjectBackupRunStatus.FAILED, request { runs.get(run.key) }?.status)
        assertEquals(before, request { storage.get("retained") })
        assertEquals(false, directory.resolve("different").toFile().exists())
        assertEquals(false, directory.resolve("retained").toFile().exists())
        assertTrue(request { runs.items(run.key, 0, 10) }.all { it.status == ObjectBackupProgressStatus.PENDING })
    }

    @Test
    fun `terminal outcomes return without configurations and active runs remain untouched`(): Unit = runBlocking {
        val completedRun = configureBackup()
        val completed = runs.executeConfigured(completedRun.key, configurations)
        assertEquals(completed, runs.executeConfigured(completed.key, emptyMap()))
        val plan = request { runs.getStorage(completed.key) }
        assertEquals(plan, request { runs.configureStorage(completed.key, "retained", mapping) })
        val cancelledRun = prepare(inventory.copy(id = "cancelled-inventory"))
        val cancelled = request { runs.cancel(cancelledRun.key, cancelledRun.version, "operator cancelled") }
        assertEquals(cancelled, runs.executeConfigured(cancelled.key, emptyMap()))
        val active = configureBackup(inventory.copy(id = "active-inventory"))
        val running = request { runs.start(active.key, active.version) }
        assertFailsWith<IllegalStateException> { runs.executeConfigured(active.key, configurations) }
        assertEquals(running, request { runs.get(active.key) })
    }

    @Test
    fun `execution requires saved mappings and rejects active caller transactions`(): Unit = runBlocking {
        val run = prepare()
        assertFailsWith<IllegalStateException> { runs.executeConfigured(run.key, configurations) }
        assertEquals(run, request { runs.get(run.key) })
        request { runs.configureStorage(run.key, "retained", mapping) }
        assertFailsWith<IllegalStateException> {
            request { transaction { runs.executeConfigured(run.key, configurations) } }
        }
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `restore mapping rejects different bytes at a shared physical target key`(): Unit = runBlocking {
        val run = prepareRestore(inventory)
        val shared = mapOf("content" to "target-content", "artifacts" to "target-content")
        assertFailsWith<IllegalArgumentException> { request { runs.configureStorage(run.key, "retained", shared) } }
        assertNull(request { runs.getStorage(run.key) })
    }

    @Test
    fun `restore mapping preserves preflight rejection of overlapping identical target keys`(): Unit = runBlocking {
        val selected = inventory.copy(objects = listOf(first, first.copy(store = "artifacts")))
        val run = prepareRestore(selected)
        val shared = mapOf("content" to "target-content", "artifacts" to "target-content")
        assertFailsWith<IllegalArgumentException> { request { runs.configureStorage(run.key, "retained", shared) } }
        assertNull(request { runs.getStorage(run.key) })
    }

    @Test
    fun `restore mapping permits shared storage with disjoint object keys`(): Unit = runBlocking {
        val selected = inventory.copy(objects = listOf(first, second.copy(key = "artifact-asset")))
        val run = prepareRestore(selected)
        val shared = mapOf("content" to "target-content", "artifacts" to "target-content")
        request { runs.configureStorage(run.key, "retained", shared) }
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(run.key, configurations).status)
        assertEquals("content", read("target-content", first.key))
        assertEquals("artifact", read("target-content", "artifact-asset"))
    }

    @Test
    fun `restore destination mappings remain fixed across later inventories`(): Unit = runBlocking {
        val firstRun = configureRestore()
        val firstPlan = request { runs.getStorage(firstRun.key) }
        val later = prepareRestore(inventory.copy(id = "later-inventory"), "manifests/later.json")
        assertFailsWith<IllegalArgumentException> {
            request { runs.configureStorage(later.key, "retained", targets + ("content" to "source-content")) }
        }
        assertNull(request { runs.getStorage(later.key) })
        assertEquals(firstPlan, request { runs.getStorage(firstRun.key) })
        assertNotNull(request { runs.configureStorage(later.key, "retained", targets) })
    }

    @Test
    fun `concurrent restore runs cannot choose different locations for one destination store`(): Unit = runBlocking {
        val firstRun = prepareRestore(inventory)
        val secondRun = prepareRestore(inventory.copy(id = "another-inventory"), "manifests/another.json")
        val alternatives = listOf(
            firstRun to targets,
            secondRun to mapOf("content" to "target-artifacts", "artifacts" to "target-content"),
        )
        val results = alternatives.map { (run, selected) ->
            async {
                try {
                    request { runs.configureStorage(run.key, "retained", selected) }
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(1, request { listOf(runs.getStorage(firstRun.key), runs.getStorage(secondRun.key)).count { it != null } })
    }

    @Test
    fun `conflicting restore mapping rolls back earlier claims for new logical stores`(): Unit = runBlocking {
        val firstRun = prepareRestore(inventory.copy(objects = listOf(first)))
        request { runs.configureStorage(firstRun.key, "retained", mapOf("content" to "target-content")) }
        val later = prepareRestore(inventory.copy(id = "expanded-inventory"), "manifests/expanded.json")
        assertFailsWith<IllegalArgumentException> {
            request {
                runs.configureStorage(later.key, "retained", mapOf(
                    "artifacts" to "source-artifacts", "content" to "source-content",
                ))
            }
        }
        assertNull(request { runs.getStorage(later.key) })
        assertEquals(targets, request { runs.configureStorage(later.key, "retained", targets) }.stores)
    }

    @Test
    fun `coroutine cancellation closes configured stores and records a cancelled attempt`(): Unit = runBlocking {
        val run = configureBackup()
        val opened = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        val tracked = object : ObjectStorageServiceFactory by factory {
            override suspend fun <T> withStorage(
                configuration: StorageConfiguration,
                block: suspend (ObjectStorageService) -> T,
            ): T {
                val key = Path.of(requireNotNull(configuration.basePath)).fileName.toString()
                return try {
                    factory.withStorage(configuration) {
                        if (key == "source-content") {
                            assertEquals(0, pool.activeConnections)
                            opened.complete(Unit)
                            awaitCancellation()
                        }
                        block(it)
                    }
                } finally {
                    closed.add(key)
                }
            }
        }
        val scoped = ObjectBackupStorageServiceImpl(provide(), tracked)
        val configuredRuns = ObjectBackupRunServiceImpl(provide(), provide(), provide(), Json, scoped)
        val attempt = async { configuredRuns.executeConfigured(run.key, configurations) }
        withTimeout(30_000) { opened.await() }

        attempt.cancelAndJoin()

        assertEquals(listOf("source-content", "source-artifacts", "retained"), closed)
        val cancelled = assertNotNull(request { runs.get(run.key) })
        assertEquals(ObjectBackupRunStatus.CANCELLED, cancelled.status)
        assertEquals(1, cancelled.attempts)
        assertEquals(2L, cancelled.result?.pending)
        assertEquals(mapping, request { runs.getStorage(run.key) }?.stores)
    }

    @Test
    fun `persisted mappings protect referenced storage records`(): Unit = runBlocking {
        configureBackup()
        for (key in mapping.values + "retained") {
            assertFailsWith<Exception> {
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    connection.prepareStatement("delete from backup.object_stores where key = ?").use {
                        it.setString(1, key)
                        it.executeUpdate()
                    }
                }
            }
            assertNotNull(request { storage.get(key) })
        }
    }

    @Test
    fun `all address claims roll back before any store opens when one conflicts`(): Unit = runBlocking {
        var invoked = false
        val selected = mapOf(
            "fresh" to StorageConfiguration("fs", "", basePath = directory.resolve("fresh").toString()),
            "retained" to StorageConfiguration("fs", "", basePath = directory.resolve("changed").toString()),
        )
        assertFailsWith<IllegalArgumentException> { storage.withStorages(selected) { invoked = true } }
        assertEquals(false, invoked)
        assertNull(request { storage.get("fresh") })
        assertEquals(false, directory.resolve("fresh").toFile().exists())
    }

    @Test
    fun `multi-store scope uses captured configurations and releases every scope on failure or cancellation`(): Unit = runBlocking {
        for (failure in listOf(IOException("work failed"), CancellationException("work cancelled"))) {
            val closed = mutableListOf<String>()
            val selected = configurations.filterKeys { it in mapping.values }.toMutableMap()
            val tracked = object : ObjectStorageServiceFactory by factory {
                override suspend fun <T> withStorage(
                    configuration: StorageConfiguration,
                    block: suspend (ObjectStorageService) -> T,
                ): T {
                    selected["source-content"] = StorageConfiguration(
                        "fs", "", basePath = directory.resolve("wrong").toString(),
                    )
                    return try {
                        factory.withStorage(configuration, block)
                    } finally {
                        closed.add(Path.of(requireNotNull(configuration.basePath)).fileName.toString())
                    }
                }
            }
            val scoped = ObjectBackupStorageServiceImpl(provide(), tracked)
            val thrown = assertFailsWith<Exception> {
                scoped.withStorages(selected) {
                    assertEquals(0, pool.activeConnections)
                    assertEquals("content", it.getValue("source-content").getString(StringObjectPath(first.key)))
                    assertEquals("artifact", it.getValue("source-artifacts").getString(StringObjectPath(second.key)))
                    throw failure
                }
            }
            assertTrue(generateSequence(thrown as Throwable) { it.cause }.any { it === failure })
            assertEquals(mapping.values.sortedDescending(), closed)
        }
    }

    @Test
    fun `later setup failure closes earlier stores without invoking work`(): Unit = runBlocking {
        val closed = mutableListOf<String>()
        val failure = IOException("later store setup failed")
        val tracked = object : ObjectStorageServiceFactory by factory {
            override suspend fun <T> withStorage(
                configuration: StorageConfiguration,
                block: suspend (ObjectStorageService) -> T,
            ): T {
                val key = Path.of(requireNotNull(configuration.basePath)).fileName.toString()
                if (key == "source-content") throw failure
                return try {
                    factory.withStorage(configuration, block)
                } finally {
                    closed.add(key)
                }
            }
        }
        val scoped = ObjectBackupStorageServiceImpl(provide(), tracked)
        var invoked = false
        val thrown = assertFailsWith<IOException> {
            scoped.withStorages(configurations.filterKeys { it in mapping.values }) { invoked = true }
        }
        assertEquals(failure.message, thrown.message)
        assertEquals(false, invoked)
        assertEquals(listOf("source-artifacts"), closed)
    }

    private suspend fun prepare(selected: ObjectBackupManifest = inventory): ObjectBackupRun =
        request { runs.prepare(selected, "retained") }

    private suspend fun configureBackup(selected: ObjectBackupManifest = inventory): ObjectBackupRun {
        val run = prepare(selected)
        request { runs.configureStorage(run.key, "retained", mapping) }
        return run
    }

    private suspend fun prepareRestore(
        selected: ObjectBackupManifest,
        inputPath: String = manifestPath,
    ): ObjectBackupRun {
        write("retained", first.payloadPath, "content")
        write("retained", second.payloadPath, "artifact")
        factory.withStorage(configurations.getValue("retained")) {
            ObjectBackupManifestStore(it, Json).save(StringObjectPath(inputPath), selected)
        }
        return request { runs.prepareRestore(selected, "restore-destination", inputPath) }
    }

    private suspend fun configureRestore(): ObjectBackupRun {
        val run = prepareRestore(inventory)
        request { runs.configureStorage(run.key, "retained", targets) }
        return run
    }

    private suspend fun write(store: String, key: String, value: String) {
        factory.withStorage(configurations.getValue(store)) {
            val bytes = value.toByteArray()
            it.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
        }
    }

    private suspend fun read(store: String, key: String): String =
        factory.withStorage(configurations.getValue(store)) { it.getString(StringObjectPath(key)) }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private fun entry(store: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject(store, "asset", "revision-1", bytes.size.toLong(), digest, "objects/$digest")
    }
}
