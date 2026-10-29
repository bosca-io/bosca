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
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real generated services, PostgreSQL and signed reads from independent S3-compatible recovery storage. */
@OptIn(InternalDI::class)
class ObjectBackupRestorePreparationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var server: HttpServer
    private lateinit var application: BoscaApplication
    private lateinit var runs: ObjectBackupRunService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private val manifestPath = "manifests/recovery.json"
    private val targets = mapOf("content" to "target-content", "artifacts" to "target-artifacts")
    private val inventory = ObjectBackupManifest(
        "snapshot", "unavailable-source", "content", "2026-10-18T00:00:00Z",
        listOf(entry("content", "metadata/item/1/content", "data"), entry("artifacts", "packages/item", "more")),
    )
    private val data = ConcurrentHashMap<String, String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val signatures = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val closed = CopyOnWriteArrayList<String>()
    private val writes = CopyOnWriteArrayList<String>()
    private var failedWrite: String? = null
    private var cancelManifest = false
    private var checkPool = true

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_restore_preparation").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4),
            key = "restore-preparation-test",
        ))
        directory = createTempDirectory("bosca-restore-preparation-")
        data[manifestPath] = Json.encodeToString(ObjectBackupManifest.serializer(), inventory)
        data[inventory.objects[0].payloadPath] = "data"
        data[inventory.objects[1].payloadPath] = "more"
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                if (checkPool) assertEquals(0, pool.activeConnections)
                val path = exchange.requestURI.path.removePrefix("/retained/")
                requests.add("${exchange.requestMethod} $path")
                signatures.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                val value = data[path]
                val bytes = (value ?: "<Error><Code>NoSuchKey</Code></Error>").toByteArray()
                exchange.sendResponseHeaders(if (value == null) 404 else 200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        configurations = mapOf(
            "retained" to StorageConfiguration(
                "s3", "", bucket = "retained", region = "us-east-1", forcePathStyle = true,
                endpoint = "http://127.0.0.1:${server.address.port}", accessKeyId = "RESTORE_PREPARE_KEY", accessKeySecret = "test-secret",
            ),
            "target-content" to StorageConfiguration("fs", "", basePath = directory.resolve("target-content").toString()),
            "target-artifacts" to StorageConfiguration("fs", "", basePath = directory.resolve("target-artifacts").toString()),
        )
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        val factory = provide<ObjectStorageServiceFactory>()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by factory {
                override suspend fun create(configuration: StorageConfiguration): ManagedObjectStorageService {
                    if (checkPool) assertEquals(0, pool.activeConnections)
                    val actual = factory.create(configuration)
                    val id = configuration.bucket ?: Path.of(checkNotNull(configuration.basePath)).fileName.toString()
                    opened.add(id)
                    return object : ManagedObjectStorageService by actual {
                        override suspend fun getInputStream(path: ObjectPath): InputStream {
                            if (cancelManifest && id == "retained" && path.toString() == manifestPath) {
                                throw CancellationException("manifest read cancelled")
                            }
                            return actual.getInputStream(path)
                        }

                        override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                            if (checkPool) assertEquals(0, pool.activeConnections)
                            writes.add("$id/$path")
                            if (id == failedWrite) {
                                actual.setInputStream(path, "x".byteInputStream(), 1)
                                throw IOException("target write failed")
                            }
                            return actual.setInputStream(path, stream, length)
                        }

                        override fun close() {
                            if (checkPool) assertEquals(0, pool.activeConnections)
                            closed.add(id)
                            actual.close()
                        }
                    }
                }

                override suspend fun <T> withStorage(
                    configuration: StorageConfiguration,
                    block: suspend (ManagedObjectStorageService) -> T,
                ): T = create(configuration).use { block(it) }
            }
        }
        install()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::application.isInitialized) application.shutdown()
            if (::server.isInitialized) server.stop(0)
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `preparation reads only the independent manifest and atomically records fixed targets`(): Unit = runBlocking {
        val run = prepare()
        assertEquals(ObjectBackupRunStatus.PENDING, run.status)
        assertEquals(inventory, run.inventory)
        assertEquals(manifestPath, run.path)
        assertEquals(listOf("GET $manifestPath"), requests.toList())
        assertTrue(signatures.all { "Credential=RESTORE_PREPARE_KEY/" in it })
        assertEquals(listOf("retained"), opened.toList())
        assertEquals(opened.toList(), closed.toList())
        assertTrue(writes.isEmpty())
        assertEquals(targets, withConnectionManager { assertNotNull(runs.getStorage(run.key)).stores })
        assertEquals(2, withConnectionManager { runs.items(run.key, 0, 20) }.size)
        assertEquals(listOf(run), history())
    }

    @Test
    fun `restore completes at original object keys without source credentials or a source catalog entry`(): Unit = runBlocking {
        val run = prepare()
        val result = runs.executeConfigured(run.key)
        assertEquals(ObjectBackupRunStatus.COMPLETED, result.status)
        assertEquals("data", directory.resolve("target-content/metadata/item/1/content").toFile().readText())
        assertEquals("more", directory.resolve("target-artifacts/packages/item").toFile().readText())
        assertEquals(2, writes.size)
        assertEquals(opened.sorted(), closed.sorted())
        assertTrue(signatures.all { "Credential=RESTORE_PREPARE_KEY/" in it })
    }

    @Test
    fun `saved request survives restart and replays without catalog access or manifest reread`(): Unit = runBlocking {
        val first = prepare()
        requests.clear(); opened.clear(); closed.clear()
        install(emptyMap())
        assertEquals(first, prepare())
        assertTrue(requests.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `changed preparation arguments cannot redirect a saved request`(): Unit = runBlocking {
        val original = prepare()
        requests.clear()
        for (request in listOf(
            suspend { runs.prepareRestoreConfigured("request", "other-destination", "retained", manifestPath, targets) },
            suspend { runs.prepareRestoreConfigured("request", "destination", "other-backup", manifestPath, targets) },
            suspend { runs.prepareRestoreConfigured("request", "destination", "retained", "other.json", targets) },
            suspend { runs.prepareRestoreConfigured("request", "destination", "retained", manifestPath, targets + ("content" to "other")) },
        )) assertFailsWith<IllegalArgumentException> { request() }
        assertEquals(listOf(original), history())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `replacing a saved manifest cannot select a new restore inventory on retry`(): Unit = runBlocking {
        val original = prepare()
        data[manifestPath] = Json.encodeToString(ObjectBackupManifest.serializer(), inventory.copy(id = "replacement"))
        requests.clear()
        assertEquals(original, prepare())
        assertTrue(requests.isEmpty())
        assertFailsWith<IllegalStateException> { runs.executeConfigured(original.key) }
        assertTrue(writes.isEmpty())
        assertEquals(ObjectBackupRunStatus.FAILED, withConnectionManager { assertNotNull(runs.get(original.key)).status })
    }

    @Test
    fun `partial failure and service restart resume without rewriting completed targets`(): Unit = runBlocking {
        val original = prepare()
        failedWrite = "target-artifacts"
        assertFailsWith<IOException> { runs.executeConfigured(original.key) }
        assertEquals(ObjectBackupRunStatus.PARTIAL, withConnectionManager { assertNotNull(runs.get(original.key)).status })
        assertEquals(2, writes.size)
        assertEquals(opened.sorted(), closed.sorted())
        writes.clear(); requests.clear()
        failedWrite = null
        install()
        val retry = prepare()
        assertEquals(original.key, retry.key)
        assertTrue(requests.isEmpty())
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(retry.key).status)
        assertEquals(listOf("target-artifacts/packages/item"), writes.toList())
    }

    @Test
    fun `completed redelivery returns its historical result without opening storage`(): Unit = runBlocking {
        val run = prepare()
        val completed = runs.executeConfigured(run.key)
        requests.clear(); opened.clear(); closed.clear(); writes.clear()
        install(emptyMap())
        val replay = prepare()
        assertEquals(completed, replay)
        assertEquals(completed, runs.executeConfigured(replay.key))
        assertTrue(requests.isEmpty())
        assertTrue(opened.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a fresh request verifies existing targets instead of returning an older completed run`(): Unit = runBlocking {
        val original = runs.executeConfigured(prepare().key)
        writes.clear()
        requests.clear()
        val fresh = runs.prepareRestoreConfigured("new-request", "destination", "retained", manifestPath, targets)
        assertNotEquals(original.key, fresh.key)
        assertEquals(ObjectBackupRunStatus.PENDING, fresh.status)
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(fresh.key).status)
        assertTrue(writes.isEmpty())
        assertTrue(requests.none { request -> inventory.objects.any { request == "GET ${it.payloadPath}" } })
        assertEquals(2, history().size)
    }

    @Test
    fun `a fresh request repairs a target lost after an earlier completed restore`(): Unit = runBlocking {
        val original = runs.executeConfigured(prepare().key)
        directory.resolve("target-content/metadata/item/1/content").toFile().delete()
        writes.clear()
        val fresh = runs.prepareRestoreConfigured("new-request", "destination", "retained", manifestPath, targets)
        assertNotEquals(original.key, fresh.key)
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(fresh.key).status)
        assertEquals(listOf("target-content/metadata/item/1/content"), writes.toList())
        assertEquals("data", directory.resolve("target-content/metadata/item/1/content").toFile().readText())
    }

    @Test
    fun `missing corrupt or invalid manifests leave no restore request or target writes`(): Unit = runBlocking {
        for (invalid in listOf<String?>(null, "not json", "{}")) {
            if (invalid == null) data.remove(manifestPath) else data[manifestPath] = invalid
            assertFailsWith<Exception> { prepare() }
            assertTrue(history().isEmpty())
            assertTrue(writes.isEmpty())
        }
        data[manifestPath] = Json.encodeToString(ObjectBackupManifest.serializer(), inventory)
        assertEquals(ObjectBackupRunStatus.PENDING, prepare().status)
    }

    @Test
    fun `invalid logical targets roll back the run and request together`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { prepare(stores = targets - "artifacts") }
        assertTrue(history().isEmpty())
        assertTrue(writes.isEmpty())
        assertEquals(ObjectBackupRunStatus.PENDING, prepare().status)
    }

    @Test
    fun `missing required retained payloads fail before selected target replacement`(): Unit = runBlocking {
        val target = directory.resolve("target-artifacts/packages/item").toFile().apply { parentFile.mkdirs(); writeText("old bytes") }
        val run = prepare()
        val retainedPath = inventory.objects.last().payloadPath
        val retainedData = data.remove(retainedPath)
        assertFailsWith<Exception> { runs.executeConfigured(run.key) }
        assertTrue(writes.isEmpty())
        assertEquals("old bytes", target.readText())
        data[retainedPath] = assertNotNull(retainedData)
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(run.key).status)
        assertEquals("more", target.readText())
    }

    @Test
    fun `physical aliases and malformed selected configuration fail before opening any store`(): Unit = runBlocking {
        install(catalog() + ("target-content" to Json.encodeToJsonElement(StorageConfiguration.serializer(), configurations.getValue("retained"))))
        assertFailsWith<IllegalArgumentException> { prepare() }
        assertTrue(opened.isEmpty())
        assertTrue(history().isEmpty())
        install(catalog() + ("target-content" to JsonPrimitive("invalid")))
        assertFailsWith<SerializationException> { prepare() }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `empty inventory is explicitly prepared and restored with no targets`(): Unit = runBlocking {
        data[manifestPath] = Json.encodeToString(ObjectBackupManifest.serializer(), inventory.copy(objects = emptyList()))
        val run = prepare(stores = emptyMap())
        assertEquals(ObjectBackupRunStatus.COMPLETED, runs.executeConfigured(run.key).status)
        assertTrue(writes.isEmpty())
        assertTrue(opened.all { it == "retained" })
    }

    @Test
    fun `cancellation closes recovery storage and leaves no partial request`(): Unit = runBlocking {
        cancelManifest = true
        assertFailsWith<CancellationException> { prepare() }
        assertTrue(history().isEmpty())
        assertEquals(opened.toList(), closed.toList())
        cancelManifest = false
        assertEquals(ObjectBackupRunStatus.PENDING, prepare().status)
    }

    @Test
    fun `independent matching callers create exactly one complete restore request`(): Unit = runBlocking {
        checkPool = false
        val results = (1..3).map { async { prepare() } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(results.distinct(), history())
    }

    @Test
    fun `conflicting concurrent requests keep one selection and roll back the losing run`(): Unit = runBlocking {
        checkPool = false
        val results = listOf("first-target", "second-target").map { destination -> async {
            try {
                runs.prepareRestoreConfigured("request", destination, "retained", manifestPath, targets)
            } catch (_: IllegalArgumentException) {
                null
            }
        } }.awaitAll().filterNotNull()
        assertEquals(1, results.size)
        val selected = results.single()
        val saved = withConnectionManager {
            listOf("first-target", "second-target").flatMap { destination ->
                runs.list(ObjectBackupOperation.RESTORE, inventory.sourceId, destination, inventory.scope, 0, 20)
            }
        }
        assertEquals(listOf(selected), saved)
        assertEquals(selected, runs.prepareRestoreConfigured("request", selected.destinationId, "retained", manifestPath, targets))
    }

    @Test
    fun `blank identities unsafe paths and active transactions fail before storage access`(): Unit = runBlocking {
        for (invalid in listOf(
            suspend { runs.prepareRestoreConfigured("", "destination", "retained", manifestPath, targets) },
            suspend { runs.prepareRestoreConfigured("request", "", "retained", manifestPath, targets) },
            suspend { runs.prepareRestoreConfigured("request", "destination", "", manifestPath, targets) },
            suspend { runs.prepareRestoreConfigured("request", "destination", "retained", "../manifest.json", targets) },
            suspend { runs.prepareRestoreConfigured("request", "destination", "retained", manifestPath, targets + ("content" to "retained")) },
        )) assertFailsWith<IllegalArgumentException> { invalid() }
        assertFailsWith<IllegalStateException> { withConnectionManager { transaction { prepare() } } }
        assertTrue(opened.isEmpty())
        assertTrue(history().isEmpty())
    }

    private suspend fun prepare(stores: Map<String, String> = targets) =
        runs.prepareRestoreConfigured("request", "destination", "retained", manifestPath, stores)

    private suspend fun history() = withConnectionManager {
        runs.list(ObjectBackupOperation.RESTORE, inventory.sourceId, "destination", inventory.scope, 0, 20)
    }

    private fun catalog(): Map<String, JsonElement> = configurations.mapValues { (_, value) ->
        Json.encodeToJsonElement(StorageConfiguration.serializer(), value)
    }

    private suspend fun install(entries: Map<String, JsonElement> = catalog()) {
        if (::application.isInitialized) application.shutdown()
        val root = buildJsonObject { put("backup", buildJsonObject { put("objectStores", JsonObject(entries)) }) }
        application = BoscaApplication(ApplicationConfig.load(root.toString().byteInputStream()), ownsServices = false)
        provides<BoscaApplication>(singleton = true, overrideExisting = true) { application }
        BackupProviderRegistrar().register()
        runs = provide()
    }

    private fun entry(store: String, key: String, value: String) = BackupObject(
        store, key, "revision", value.length.toLong(), MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString(),
        "payloads/$store",
    )
}
