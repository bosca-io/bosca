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
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Generated DI, persisted mappings and real storage using application-owned configurations. */
@OptIn(InternalDI::class)
class ObjectBackupApplicationStorageIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var application: BoscaApplication
    private lateinit var runs: ObjectBackupRunService
    private lateinit var storage: ObjectBackupStorageService
    private lateinit var entries: Map<String, JsonElement>
    private val sourceKey = "source.primary/二"
    private val backupKey = "retained.backup"
    private val targetKey = "target.copy"
    private val first = entry("items/a", "data")
    private val second = entry("items/b", "more")
    private val inventory = ObjectBackupManifest(
        "application-inventory", "source-instance", "content", "2026-10-17T00:00:00Z", listOf(first, second),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_application_storage").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-application-storage-test",
        ))
        directory = createTempDirectory("bosca-application-storage-")
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        entries = mapOf(
            sourceKey to configuration("source"),
            backupKey to configuration("retained"),
            targetKey to configuration("target"),
        )
        install()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
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
    fun `selected literal IDs preserve defaults and ignore unrelated invalid entries`(): Unit = runBlocking {
        val selected = JsonObject(mapOf(
            "type" to JsonPrimitive("fs"),
            "urlPrefix" to JsonPrimitive(""),
            "basePath" to JsonPrimitive(directory.resolve("source").toString()),
            "futureSetting" to JsonPrimitive(true),
        ))
        install(JsonObject(mapOf(sourceKey to selected, "unused" to JsonPrimitive("invalid"))))

        assertEquals(mapOf(sourceKey to StorageConfiguration(
            "fs", "", basePath = directory.resolve("source").toString(),
        )), storage.configurations(setOf(sourceKey)))
        assertEquals(0, pool.activeConnections)
        assertNull(request { storage.get(sourceKey) })
        assertFalse(directory.resolve("source").toFile().exists())
    }

    @Test
    fun `empty selections require no catalog and perform no binding`(): Unit = runBlocking {
        install(null)
        assertEquals(emptyMap(), storage.configurations(emptySet()))
        assertEquals(emptyList(), request { storage.bindConfigured(emptySet()) })
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `missing catalog never falls back to shared content storage`(): Unit = runBlocking {
        install(null)
        assertFailsWith<IllegalStateException> { storage.configurations(setOf("content")) }
        assertNull(request { storage.get("content") })
        assertFalse(directory.resolve("shared-content").toFile().exists())
    }

    @Test
    fun `missing and blank selected identities fail without binding`(): Unit = runBlocking {
        for (keys in listOf(setOf("missing"), setOf(""), setOf(" "))) {
            assertFailsWith<IllegalArgumentException> { storage.configurations(keys) }
            assertFailsWith<IllegalArgumentException> { request { storage.bindConfigured(keys) } }
        }
        assertNull(request { storage.get("missing") })
    }

    @Test
    fun `malformed selected entries and catalog fail only when selected`(): Unit = runBlocking {
        for (invalid in listOf(JsonPrimitive("invalid"), JsonNull, JsonObject(emptyMap()))) {
            install(JsonObject(entries + (sourceKey to invalid)))
            assertFailsWith<SerializationException> { storage.configurations(setOf(sourceKey)) }
            assertNotNull(storage.configurations(setOf(backupKey))[backupKey])
            assertNull(request { storage.get(sourceKey) })
        }
        install(JsonPrimitive("invalid"))
        assertFailsWith<SerializationException> { storage.configurations(setOf(sourceKey)) }
    }

    @Test
    fun `all selected entries and addresses validate before binding`(): Unit = runBlocking {
        for (invalid in listOf(
            JsonPrimitive("invalid"),
            configuration("invalid").let { JsonObject(it + ("type" to JsonPrimitive("noop"))) },
            configuration("invalid").let { JsonObject(it + ("basePath" to JsonPrimitive(" "))) },
        )) {
            install(JsonObject(entries + ("z-invalid" to invalid)))
            val failure = assertFailsWith<Exception> { storage.bindConfigured(setOf(sourceKey, "z-invalid")) }
            if (invalid is JsonPrimitive) {
                assertIs<SerializationException>(failure)
            } else {
                assertIs<IllegalArgumentException>(failure)
            }
            assertNull(request { storage.get(sourceKey) })
            assertNull(request { storage.get("z-invalid") })
        }
        assertFalse(directory.resolve("source").toFile().exists())
    }

    @Test
    fun `later binding conflict rolls back earlier claims`(): Unit = runBlocking {
        val original = request { storage.bindConfigured(setOf(backupKey)) }.single()
        install(JsonObject(entries + (backupKey to configuration("changed")) + ("a-new" to configuration("fresh"))))

        assertFailsWith<IllegalArgumentException> {
            request { storage.bindConfigured(setOf("a-new", backupKey)) }
        }

        assertNull(request { storage.get("a-new") })
        assertEquals(original, request { storage.get(backupKey) })
        assertFalse(directory.resolve("fresh").toFile().exists())
    }

    @Test
    fun `configured binding participates in caller rollback`(): Unit = runBlocking {
        assertFailsWith<IOException> {
            request {
                transaction {
                    storage.bindConfigured(setOf(sourceKey, backupKey))
                    throw IOException("abort binding")
                }
            }
        }
        assertNull(request { storage.get(sourceKey) })
        assertNull(request { storage.get(backupKey) })
    }

    @Test
    fun `application configured backup and restore verify original payloads`(): Unit = runBlocking {
        val run = prepareBackup()
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(run.key).status)
        val restore = request {
            runs.prepareRestore(inventory, "restored-instance", run.path).also {
                runs.configureStorage(it.key, backupKey, mapOf("content" to targetKey))
            }
        }

        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(restore.key).status)

        assertEquals("data", read(targetKey, first.key))
        assertEquals("more", read(targetKey, second.key))
        assertEquals(mapOf("content" to sourceKey), request { runs.getStorage(run.key) }?.stores)
        assertEquals(mapOf("content" to targetKey), request { runs.getStorage(restore.key) }?.stores)
    }

    @Test
    fun `application configured partial retry reuses earlier verified bytes`(): Unit = runBlocking {
        val run = prepareBackup()
        directory.resolve("source").resolve(second.key).toFile().delete()
        assertFailsWith<Exception> { runs.executeConfigured(run.key) }
        assertEquals(ObjectBackupRunStatus.PARTIAL, request { runs.get(run.key) }?.status)
        directory.resolve("source").resolve(first.key).toFile().delete()
        write(sourceKey, second.key, "more")

        val completed = runs.executeConfigured(run.key)

        assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempts)
        assertEquals(2L, completed.result?.completed)
    }

    @Test
    fun `missing or malformed selected configuration leaves the run untouched`(): Unit = runBlocking {
        val run = prepareBackup()
        for (catalog in listOf(
            null,
            JsonObject(entries - sourceKey),
            JsonObject(entries + (sourceKey to JsonPrimitive("invalid"))),
        )) {
            install(catalog)
            assertFailsWith<Exception> { runs.executeConfigured(run.key) }
            assertEquals(run, request { runs.get(run.key) })
        }
        assertFalse(directory.resolve("retained").toFile().exists())
    }

    @Test
    fun `changed application location fails before object transfer`(): Unit = runBlocking {
        val run = prepareBackup()
        val original = request { storage.get(backupKey) }
        install(JsonObject(entries + (backupKey to configuration("changed"))))

        assertFailsWith<IllegalArgumentException> { runs.executeConfigured(run.key) }

        assertEquals(ObjectBackupRunStatus.FAILED, request { runs.get(run.key) }?.status)
        assertEquals(original, request { storage.get(backupKey) })
        assertFalse(directory.resolve("changed").toFile().exists())
        assertFalse(directory.resolve("retained").toFile().exists())
    }

    @Test
    fun `historical outcomes and active run rejection precede catalog lookup`(): Unit = runBlocking {
        val run = prepareBackup()
        val completed = runs.executeConfigured(run.key)
        val cancelled = request {
            runs.prepare(inventory.copy(id = "cancelled"), backupKey).let {
                runs.cancel(it.key, it.version, "operator cancelled")
            }
        }
        val active = request {
            runs.prepare(inventory.copy(id = "active"), backupKey).let {
                runs.start(it.key, it.version)
            }
        }
        install(JsonPrimitive("invalid"))

        assertEquals(completed, runs.executeConfigured(completed.key))
        assertEquals(cancelled, runs.executeConfigured(cancelled.key))
        assertFailsWith<IllegalStateException> { runs.executeConfigured(active.key) }
        assertEquals(active, request { runs.get(active.key) })
    }

    @Test
    fun `application execution rejects a caller transaction before catalog access`(): Unit = runBlocking {
        val run = prepareBackup()
        install(JsonPrimitive("invalid"))
        assertFailsWith<IllegalStateException> { request { transaction { runs.executeConfigured(run.key) } } }
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `restart rotates S3 credentials while preserving the recorded location`(): Unit = runBlocking {
        val headers = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            headers.add(exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("/assets/asset", exchange.requestURI.path)
            val bytes = "data".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val selected = StorageConfiguration(
                "s3", "", bucket = "assets", region = "us-east-1", forcePathStyle = true,
                endpoint = "http://127.0.0.1:" + server.address.port,
                accessKeyId = "FIRST_KEY", accessKeySecret = "FIRST_SECRET",
            )
            install(JsonObject(mapOf(backupKey to Json.encodeToJsonElement(StorageConfiguration.serializer(), selected))))
            val bound = request { storage.bindConfigured(setOf(backupKey)) }.single()
            assertEquals("data", read(backupKey, "asset"))
            install(JsonObject(mapOf(backupKey to Json.encodeToJsonElement(
                StorageConfiguration.serializer(),
                selected.copy(accessKeyId = "ROTATED_KEY", accessKeySecret = "ROTATED_SECRET"),
            ))))

            assertEquals(bound, request { storage.bindConfigured(setOf(backupKey)) }.single())
            assertEquals("data", read(backupKey, "asset"))
            assertEquals(2, headers.size)
            assertTrue(headers[0].contains("Credential=FIRST_KEY/"))
            assertTrue(headers[1].contains("Credential=ROTATED_KEY/"))
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("select to_jsonb(s)::text from backup.object_stores s where key = ?").use {
                    it.setString(1, backupKey)
                    it.executeQuery().use { result ->
                        assertTrue(result.next())
                        val persisted = result.getString(1)
                        for (secret in listOf("FIRST_KEY", "FIRST_SECRET", "ROTATED_KEY", "ROTATED_SECRET")) {
                            assertFalse(persisted.contains(secret))
                        }
                    }
                }
            }
        } finally {
            server.stop(0)
        }
    }

    private suspend fun install(catalog: JsonElement? = JsonObject(entries)) {
        if (::application.isInitialized) application.shutdown()
        val root = buildJsonObject {
            if (catalog != null) put("backup", buildJsonObject { put("objectStores", catalog) })
            put("content", buildJsonObject { put("storage", configuration("shared-content")) })
        }
        application = BoscaApplication(
            ApplicationConfig.load(Json.encodeToString(JsonObject.serializer(), root).byteInputStream()),
            ownsServices = false,
        )
        provides<BoscaApplication>(singleton = true, overrideExisting = true) { application }
        BackupProviderRegistrar().register()
        runs = provide()
        storage = provide()
    }

    private fun configuration(path: String): JsonObject = Json.encodeToJsonElement(
        StorageConfiguration.serializer(), StorageConfiguration("fs", "", basePath = directory.resolve(path).toString()),
    ) as JsonObject

    private suspend fun prepareBackup(): ObjectBackupRun {
        request { storage.bindConfigured(setOf(sourceKey, backupKey, targetKey)) }
        write(sourceKey, first.key, "data")
        write(sourceKey, second.key, "more")
        return request {
            runs.prepare(inventory, backupKey).also {
                runs.configureStorage(it.key, backupKey, mapOf("content" to sourceKey))
            }
        }
    }

    private suspend fun write(key: String, path: String, value: String) {
        val selected = storage.configurations(setOf(key)).getValue(key)
        storage.withStorage(key, selected) {
            assertEquals(0, pool.activeConnections)
            val bytes = value.toByteArray()
            it.setInputStream(StringObjectPath(path), bytes.inputStream(), bytes.size.toLong())
        }
    }

    private suspend fun read(key: String, path: String): String {
        val selected = storage.configurations(setOf(key)).getValue(key)
        return storage.withStorage(key, selected) {
            assertEquals(0, pool.activeConnections)
            it.getString(StringObjectPath(path))
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private fun entry(key: String, value: String): BackupObject {
        val bytes = value.toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return BackupObject("content", key, "revision-1", bytes.size.toLong(), sha256, "objects/" + sha256)
    }
}
