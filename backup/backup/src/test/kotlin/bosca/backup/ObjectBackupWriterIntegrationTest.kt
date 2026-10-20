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
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Durable destination ownership through real PostgreSQL and generated backup providers. */
@OptIn(InternalDI::class)
class ObjectBackupWriterIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var directory: Path
    private lateinit var service: ObjectBackupRunService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private var migrated = false
    private val first = entry("content", "a", "data")
    private val second = entry("artifacts", "b", "more")
    private val inventory = ObjectBackupManifest(
        "inventory", "source", "content", "2026-10-17T00:00:00Z", listOf(first, second),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_writers").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-writers-test",
        ))
        ProviderRegistry.clear()
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()), ownsServices = false)
        provides<BoscaApplication>(singleton = true) { application }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        val migration = provide<Migration>(name = "backup-migrations")
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = migration.schema
            override val resources = migration.resources.takeWhile { !it.startsWith("V7__") }
        }))
        migrated = false
        directory = createTempDirectory("bosca-backup-writers-")
        configurations = listOf("source", "backup-a", "backup-b", "target-a", "target-b").associateWith {
            StorageConfiguration("fs", "", basePath = directory.resolve(it).toString())
        }
        val storage = provide<ObjectBackupStorageService>()
        request { for ((key, configuration) in configurations) storage.bind(key, configuration) }
        service = provide()
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
    fun `concurrent backup runs admit one writer and leave the losing attempt untouched`(): Unit = runBlocking {
        val runs = listOf(backup("one"), backup("two"))
        val started = runs.map { run ->
            async {
                try {
                    request { service.start(run.key, run.version) }
                } catch (_: ObjectBackupDestinationBusyException) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
        assertEquals(1, started.size)
        val winner = started.single()
        assertEquals(mapOf("backup-a" to winner.key), writers())
        val loser = runs.single { it.key != winner.key }
        assertEquals(loser, request { service.get(loser.key) })
    }

    @Test
    fun `independent destinations can run concurrently`(): Unit = runBlocking {
        val firstRun = backup("one")
        val secondRun = backup("two", "backup-b")
        request { service.start(firstRun.key, firstRun.version) }
        request { service.start(secondRun.key, secondRun.version) }
        assertEquals(mapOf("backup-a" to firstRun.key, "backup-b" to secondRun.key), writers())
    }

    @Test
    fun `backup and restore operations share physical destination ownership`(): Unit = runBlocking {
        val run = backup("one")
        val restore = restore("restore", mapOf("content" to "backup-a"), listOf(first), retained = "backup-b")
        request { service.start(run.key, run.version) }
        assertFailsWith<ObjectBackupDestinationBusyException> {
            request { service.start(restore.key, restore.version) }
        }
        assertEquals(restore, request { service.get(restore.key) })
        assertEquals(mapOf("backup-a" to run.key), writers())
    }

    @Test
    fun `multi-target contention rolls back earlier claims and the run transition`(): Unit = runBlocking {
        val held = restore("held", mapOf("artifacts" to "target-b"), listOf(second))
        val heldWriter = request { service.start(held.key, held.version) }
        val waiting = restore("waiting", mapOf("content" to "target-a", "artifacts" to "target-b"))
        val failure = assertFailsWith<ObjectBackupDestinationBusyException> {
            request { service.start(waiting.key, waiting.version) }
        }
        assertEquals("target-b", failure.storageKey)
        assertEquals(waiting, request { service.get(waiting.key) })
        assertEquals(mapOf("target-b" to held.key), writers())
        val independent = restore("independent", mapOf("content" to "target-a"), listOf(first))
        val independentWriter = request { service.start(independent.key, independent.version) }
        assertEquals(mapOf("target-b" to held.key, "target-a" to independent.key), writers())
        request {
            service.fail(held.key, heldWriter.version, "writer stopped")
            service.fail(independent.key, independentWriter.version, "writer stopped")
        }
        val running = request { service.start(waiting.key, waiting.version) }
        assertEquals(mapOf("target-a" to waiting.key, "target-b" to waiting.key), writers())
        request { service.fail(waiting.key, running.version, "writer stopped") }
        assertTrue(writers().isEmpty())
    }

    @Test
    fun `logical stores sharing disjoint keys claim one physical destination`(): Unit = runBlocking {
        val run = restore("shared", mapOf("content" to "target-a", "artifacts" to "target-a"))
        request { service.start(run.key, run.version) }
        assertEquals(mapOf("target-a" to run.key), writers())
    }

    @Test
    fun `every terminal outcome releases only its own destination`(): Unit = runBlocking {
        val independent = backup("independent", "backup-b")
        request { service.start(independent.key, independent.version) }
        for (outcome in listOf("completed", "failed", "partial", "cancelled")) {
            val run = backup(outcome, objects = if (outcome == "completed") emptyList() else inventory.objects)
            val running = request { service.start(run.key, run.version) }
            if (outcome == "partial") {
                request {
                    val item = service.items(run.key, 0, 1).single()
                    val progress = provide<ObjectBackupProgressService>()
                    val active = progress.start(item.key, item.version)
                    progress.complete(active.key, active.version)
                }
            }
            val terminal = request {
                when (outcome) {
                    "completed" -> service.complete(run.key, running.version)
                    "cancelled" -> service.cancel(run.key, running.version, "writer stopped")
                    else -> service.fail(run.key, running.version, "writer stopped")
                }
            }
            assertEquals(outcome.uppercase(), terminal.status.name)
            assertEquals(mapOf("backup-b" to independent.key), writers())
        }
    }

    @Test
    fun `stale and invalid transitions cannot release a writer`(): Unit = runBlocking {
        val run = backup("one")
        val running = request { service.start(run.key, run.version) }
        assertFailsWith<IllegalStateException> { request { service.complete(run.key, running.version) } }
        assertFailsWith<IllegalStateException> { request { service.fail(run.key, run.version, "stale") } }
        assertFailsWith<IllegalStateException> { request { service.retry(run.key, running.version) } }
        assertEquals(mapOf("backup-a" to run.key), writers())
        request { service.fail(run.key, running.version, "writer stopped") }
        val later = backup("later")
        request { service.start(later.key, later.version) }
        assertFailsWith<IllegalStateException> { request { service.fail(run.key, running.version, "stale") } }
        assertEquals(mapOf("backup-a" to later.key), writers())
    }

    @Test
    fun `start and pending cancellation admit exactly one observed transition`(): Unit = runBlocking {
        val runs = listOf(
            backup("backup-race"),
            restore("restore-race", mapOf("content" to "target-a", "artifacts" to "target-b")),
        )
        for (run in runs) {
            val gate = CompletableDeferred<Unit>()
            val attempts = listOf(
                async {
                    gate.await()
                    try {
                        request { service.start(run.key, run.version) }
                    } catch (_: IllegalStateException) {
                        null
                    }
                },
                async {
                    gate.await()
                    try {
                        request { service.cancelPending(run.key, run.version, "operator cancelled") }
                    } catch (_: IllegalStateException) {
                        null
                    }
                },
            )
            gate.complete(Unit)
            val outcomes = attempts.awaitAll()
            assertEquals(1, outcomes.filterNotNull().size)
            val winner = assertNotNull(outcomes.single { it != null })
            assertEquals(winner, request { service.get(run.key) })
            assertEquals(run.version + 1, winner.version)
            val mapping = assertNotNull(request { service.getStorage(run.key) })
            if (winner.status == ObjectBackupRunStatus.RUNNING) {
                assertEquals(1, winner.attempts)
                val destinations = when (winner.operation) {
                    ObjectBackupOperation.BACKUP -> setOf(mapping.backupKey)
                    ObjectBackupOperation.RESTORE -> mapping.stores.values.toSet()
                }
                assertEquals(destinations.associateWith { run.key }, writers())
                request { service.fail(run.key, winner.version, "test writer stopped") }
            } else {
                assertEquals(ObjectBackupRunStatus.CANCELLED, winner.status)
                assertEquals(0, winner.attempts)
                assertTrue(writers().isEmpty())
            }
        }
    }

    @Test
    fun `duplicate pending cancellations change state and version once`(): Unit = runBlocking {
        val run = backup("duplicate-cancel")
        val gate = CompletableDeferred<Unit>()
        val attempts = (1..2).map {
            async {
                gate.await()
                try {
                    request { service.cancelPending(run.key, run.version, "operator cancelled") }
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }
        gate.complete(Unit)
        val cancelled = attempts.awaitAll().filterNotNull().single()
        assertEquals(ObjectBackupRunStatus.CANCELLED, cancelled.status)
        assertEquals(run.version + 1, cancelled.version)
        assertEquals(0, cancelled.attempts)
        assertEquals(cancelled, request { service.get(run.key) })
        assertTrue(writers().isEmpty())
    }

    @Test
    fun `caller rollback preserves pending cancellation retry checkpoints and other writers`(): Unit = runBlocking {
        val independent = backup("independent", "backup-b")
        request { service.start(independent.key, independent.version) }
        val run = backup("cancel-rollback")
        val checkpoints = request { service.items(run.key, 0, 100) }
        val mapping = request { service.getStorage(run.key) }
        assertFailsWith<IOException> {
            request {
                transaction {
                    service.cancelPending(run.key, run.version, "operator cancelled")
                    throw IOException("abort cancellation")
                }
            }
        }
        assertEquals(run, request { service.get(run.key) })
        assertEquals(mapOf("backup-b" to independent.key), writers())
        val cancelled = request { service.cancelPending(run.key, run.version, "operator cancelled") }
        assertFailsWith<IOException> {
            request {
                transaction {
                    service.retry(run.key, cancelled.version)
                    throw IOException("abort retry")
                }
            }
        }
        assertEquals(cancelled, request { service.get(run.key) })
        assertEquals(checkpoints, request { service.items(run.key, 0, 100) })
        assertEquals(mapping, request { service.getStorage(run.key) })
        assertEquals(mapOf("backup-b" to independent.key), writers())
    }

    @Test
    fun `caller rollback preserves state and ownership for both start and finish`(): Unit = runBlocking {
        val run = backup("one")
        assertFailsWith<IOException> {
            request {
                transaction {
                    service.start(run.key, run.version)
                    throw IOException("abort start")
                }
            }
        }
        assertEquals(run, request { service.get(run.key) })
        assertTrue(writers().isEmpty())
        val running = request { service.start(run.key, run.version) }
        assertFailsWith<IOException> {
            request {
                transaction {
                    service.fail(run.key, running.version, "stopped")
                    throw IOException("abort finish")
                }
            }
        }
        assertEquals(running, request { service.get(run.key) })
        assertEquals(mapOf("backup-a" to run.key), writers())
    }

    @Test
    fun `restart retains ownership until the stopped writer is explicitly recovered`(): Unit = runBlocking {
        val run = backup("one")
        val running = request { service.start(run.key, run.version) }
        val waiting = backup("waiting")
        BackupProviderRegistrar().register()
        service = provide()
        assertFailsWith<ObjectBackupDestinationBusyException> {
            request { service.start(waiting.key, waiting.version) }
        }
        assertEquals(running, request { service.get(run.key) })
        request { service.fail(run.key, running.version, "prior worker stopped") }
        request { service.start(waiting.key, waiting.version) }
        assertEquals(mapOf("backup-a" to waiting.key), writers())
    }

    @Test
    fun `actual partial execution releases ownership and retry completes`(): Unit = runBlocking {
        val run = backup("one")
        write(first.key, "data")
        assertFailsWith<Exception> { service.executeConfigured(run.key, configurations) }
        assertEquals(ObjectBackupRunStatus.PARTIAL, request { service.get(run.key) }?.status)
        assertTrue(writers().isEmpty())
        write(second.key, "more")
        val completed = service.executeConfigured(run.key, configurations)
        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        assertTrue(writers().isEmpty())
    }

    @Test
    fun `cancellation retains ownership until the storage scopes unwind`(): Unit = runBlocking {
        val run = backup("one")
        val entered = CompletableDeferred<Unit>()
        val factory = provide<ObjectStorageServiceFactory>()
        val observed = mutableListOf<Map<String, String>>()
        val tracked = object : ObjectStorageServiceFactory by factory {
            override suspend fun <T> withStorage(
                configuration: StorageConfiguration,
                block: suspend (ObjectStorageService) -> T,
            ): T = try {
                factory.withStorage(configuration) {
                    if (Path.of(requireNotNull(configuration.basePath)).fileName.toString() == "source") {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    block(it)
                }
            } finally {
                observed.add(writers())
            }
        }
        val stores = ObjectBackupStorageServiceImpl(provide(), tracked, application)
        val execution = ObjectBackupRunServiceImpl(provide(), provide(), provide(), Json, stores)
        val attempt = async { execution.executeConfigured(run.key, configurations) }
        withTimeout(30_000) { entered.await() }
        assertEquals(mapOf("backup-a" to run.key), writers())
        attempt.cancelAndJoin()
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it == mapOf("backup-a" to run.key) })
        assertTrue(writers().isEmpty())
        assertEquals(ObjectBackupRunStatus.CANCELLED, request { service.get(run.key) }?.status)
    }

    @Test
    fun `unconfigured histories retain their caller-owned writer contract`(): Unit = runBlocking {
        upgrade()
        val runs = request {
            listOf(
                service.prepare(inventory.copy(id = "legacy-a"), "legacy-destination"),
                service.prepare(inventory.copy(id = "legacy-b"), "legacy-destination"),
            )
        }
        for (run in runs) request { service.start(run.key, run.version) }
        assertTrue(writers().isEmpty())
    }

    @Test
    fun `migration backfills running configured writers without changing legacy history`(): Unit = runBlocking {
        val backup = seedLegacyRun("backup")
        val restore = seedLegacyRun("restore", ObjectBackupOperation.RESTORE)
        val failed = seedLegacyRun("failed")
        val legacy = seedLegacyRun("legacy", destination = "legacy-destination", configured = false)
        setLegacyStatus(backup.key, "running")
        setLegacyStatus(restore.key, "running")
        setLegacyStatus(failed.key, "failed")
        setLegacyStatus(legacy.key, "running")
        val previous = listOf(backup, restore, failed, legacy).map { legacyRun(it.key) }
        val items = legacyItems(backup.key)

        upgrade()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))

        assertEquals(mapOf("backup-a" to backup.key, "target-a" to restore.key), writers())
        assertEquals(previous, listOf(backup, restore, failed, legacy).map { legacyRun(it.key) })
        for (run in listOf(backup, restore, failed, legacy)) {
            assertNull(request { assertNotNull(service.get(run.key)).checkpointKey })
        }
        assertEquals(items, legacyItems(backup.key))
        assertEquals(inventory.objects, request { service.items(backup.key, 0, 10) }.map { it.payload })
        assertNull(request { service.getStorage(legacy.key) })
    }

    @Test
    fun `migration rejects overlapping historical writers without selecting an owner`(): Unit = runBlocking {
        val firstRun = seedLegacyRun("one")
        val secondRun = seedLegacyRun("two")
        setLegacyStatus(firstRun.key, "running")
        setLegacyStatus(secondRun.key, "running")
        assertFailsWith<Exception> { upgrade() }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select to_regclass('backup.object_store_writers')").use {
                    assertTrue(it.next())
                    assertNull(it.getString(1))
                }
            }
        }
        assertEquals("running", legacyRun(firstRun.key).getValue("status").jsonPrimitive.content)
        assertEquals("running", legacyRun(secondRun.key).getValue("status").jsonPrimitive.content)
        setLegacyStatus(secondRun.key, "failed")
        upgrade()
        assertEquals(mapOf("backup-a" to firstRun.key), writers())
    }

    private fun legacyRun(key: String) =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("select (to_jsonb(r) - 'checkpoint_key')::text from backup.object_runs r where key = ?").use { sql ->
                sql.setString(1, key)
                sql.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    Json.parseToJsonElement(rows.getString(1)).jsonObject
                }
            }
        }

    private suspend fun backup(
        id: String,
        destination: String = "backup-a",
        objects: List<BackupObject> = inventory.objects,
    ): ObjectBackupRun {
        upgrade()
        return request {
            service.prepare(inventory.copy(id = id, sourceId = id, objects = objects), destination).also {
                service.configureStorage(it.key, destination, objects.associate { entry -> entry.store to "source" })
            }
        }
    }

    private suspend fun restore(
        id: String,
        mapping: Map<String, String>,
        objects: List<BackupObject> = inventory.objects,
        retained: String = "backup-a",
    ): ObjectBackupRun {
        upgrade()
        return request {
            service.prepareRestore(inventory.copy(id = id, objects = objects), id, "manifests/" + id).also {
                service.configureStorage(it.key, retained, mapping)
            }
        }
    }

    private suspend fun seedLegacyRun(
        id: String,
        operation: ObjectBackupOperation = ObjectBackupOperation.BACKUP,
        destination: String = if (operation == ObjectBackupOperation.BACKUP) "backup-a" else id,
        configured: Boolean = true,
    ): ObjectBackupRun {
        val selected = inventory.copy(id = id, sourceId = id)
        val key = objectBackupIdentity(operation, listOf(id, destination, selected.scope, id))
        val path = "manifests/$key.json"
        val operationName = operation.name.lowercase()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.autoCommit = false
            if (operation == ObjectBackupOperation.BACKUP) {
                connection.seed("""
                    insert into backup.object_paths (destination_id, path, manifest_key) values (?, ?, ?)
                """.trimIndent(), destination, path, key)
            }
            connection.seed("""
                insert into backup.object_runs (key, source_id, destination_id, scope, operation, inventory, path)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(), key, id, destination, selected.scope, operationName,
                Json.encodeToString(ObjectBackupManifest.serializer(), selected), path)
            for ((position, payload) in selected.objects.withIndex()) {
                if (operation == ObjectBackupOperation.BACKUP) {
                    connection.prepareStatement("""
                        insert into backup.object_paths (destination_id, path, length, sha256)
                        values (?, ?, ?, ?) on conflict (destination_id, path) do nothing
                    """.trimIndent()).use {
                        it.setString(1, destination)
                        it.setString(2, payload.payloadPath)
                        it.setLong(3, payload.length)
                        it.setString(4, payload.sha256)
                        it.executeUpdate()
                    }
                }
                val progressKey = objectBackupIdentity(
                    operation, listOf(id, destination, selected.scope, payload.store, payload.key, payload.revision),
                )
                connection.seed("""
                    insert into backup.object_progress (key, source_id, destination_id, scope, operation, payload)
                    values (?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(), progressKey, id, destination, selected.scope, operationName,
                    Json.encodeToString(BackupObject.serializer(), payload))
                connection.seed("""
                    insert into backup.object_run_items (run_key, position, progress_key) values (?, ?, ?)
                """.trimIndent(), key, position, progressKey)
            }
            if (configured) {
                val backupKey = if (operation == ObjectBackupOperation.BACKUP) destination else "backup-a"
                val storageKey = if (operation == ObjectBackupOperation.BACKUP) "source" else "target-a"
                connection.seed("insert into backup.object_run_storage (key, backup_key) values (?, ?)", key, backupKey)
                for (store in selected.objects.map { it.store }.distinct()) {
                    if (operation == ObjectBackupOperation.RESTORE) {
                        connection.seed("""
                            insert into backup.object_restore_stores (destination_id, store, storage_key) values (?, ?, ?)
                        """.trimIndent(), destination, store, storageKey)
                        connection.seed("""
                            insert into backup.object_run_stores (run_key, store, storage_key, restore_destination_id)
                            values (?, ?, ?, ?)
                        """.trimIndent(), key, store, storageKey, destination)
                    } else {
                        connection.seed("""
                            insert into backup.object_run_stores (run_key, store, storage_key) values (?, ?, ?)
                        """.trimIndent(), key, store, storageKey)
                    }
                }
            }
            connection.commit()
        }
        return ObjectBackupRun(key, id, destination, selected.scope, operation, selected, path)
    }

    private fun java.sql.Connection.seed(sql: String, vararg values: Any) {
        prepareStatement(sql).use { statement ->
            for ((index, value) in values.withIndex()) statement.setObject(index + 1, value)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun legacyItems(key: String) =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("""
                select jsonb_agg(to_jsonb(p) - 'verified_version' - 'verified_info' - 'verified' order by i.position)::text
                from backup.object_run_items i join backup.object_progress p on p.key = i.progress_key
                where i.run_key = ?
            """.trimIndent()).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use {
                    assertTrue(it.next())
                    Json.parseToJsonElement(it.getString(1))
                }
            }
        }

    private suspend fun upgrade() {
        if (migrated) return
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        migrated = true
    }

    private fun writers(): Map<String, String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select storage_key, run_key from backup.object_store_writers").use {
                    buildMap { while (it.next()) put(it.getString(1), it.getString(2)) }
                }
            }
        }

    private fun setLegacyStatus(key: String, status: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("""
                update backup.object_runs set status = ?, attempts = 1, version = 1,
                    error = case when ? = 'failed' then 'legacy writer stopped' else null end,
                    result = case when ? = 'failed' then jsonb_build_object(
                        'total', 2, 'pending', 2, 'running', 0, 'completed', 0,
                        'failed', 0, 'totalBytes', 8, 'verifiedBytes', 0)
                        else null end
                where key = ?
            """.trimIndent()).use {
                it.setString(1, status)
                it.setString(2, status)
                it.setString(3, status)
                it.setString(4, key)
                it.executeUpdate()
            }
        }
    }

    private suspend fun write(key: String, value: String) {
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue("source")) {
            val bytes = value.toByteArray()
            it.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private fun entry(store: String, key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject(store, key, "revision", bytes.size.toLong(), digest, "objects/" + digest)
    }
}
