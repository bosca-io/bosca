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
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real configured capture through PostgreSQL/generated DI, signed S3 revision reads and scoped files. */
@OptIn(InternalDI::class)
class ObjectBackupCaptureConfiguredIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var server: HttpServer
    private lateinit var application: BoscaApplication
    private lateinit var service: ObjectBackupCaptureService
    private lateinit var storage: ObjectBackupStorageService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private val mapping = mapOf("content" to "source.a", "artifacts" to "source.b")
    private val data = ConcurrentHashMap<String, String>().apply {
        put("/content/asset@v1", "data")
        put("/artifacts/asset@v1", "more")
    }
    private val requests = CopyOnWriteArrayList<String>()
    private val signatures = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val closed = CopyOnWriteArrayList<String>()
    private var failOpen: String? = null
    private var failRead: String? = null
    private var cancelRead: String? = null
    private val selection = ObjectBackupCaptureInventory(
        "selected", "source-instance", "content", "2026-10-18T00:00:00Z",
        mapOf("content" to listOf(entry()), "artifacts" to listOf(entry())),
    )

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_capture_configured").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "capture-configured-test",
        ))
        directory = createTempDirectory("bosca-capture-configured-")
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { backupTestJson }
        StorageProviderRegistrar().register()
        val factory = provide<ObjectStorageServiceFactory>()
        val tracked = object : ObjectStorageServiceFactory by factory {
            override suspend fun create(configuration: StorageConfiguration): ObjectStorageService {
                assertEquals(0, pool.activeConnections)
                val id = configuration.bucket ?: Path.of(checkNotNull(configuration.basePath)).fileName.toString()
                if (id == failOpen) throw IOException("store open failed")
                val actual = factory.create(configuration)
                opened.add(id)
                return object : ObjectStorageService by actual {
                    override suspend fun getInputStream(path: ObjectPath, version: String): InputStream {
                        assertEquals(0, pool.activeConnections)
                        if (id == cancelRead) currentCoroutineContext().cancel(CancellationException("capture cancelled"))
                        return actual.getInputStream(path, version)
                    }

                    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                        assertEquals(0, pool.activeConnections)
                        return actual.setInputStream(path, stream, length)
                    }

                    override fun close() {
                        assertEquals(0, pool.activeConnections)
                        closed.add(id)
                        actual.close()
                    }
                }
            }

            override suspend fun <T> withStorage(
                configuration: StorageConfiguration,
                block: suspend (ObjectStorageService) -> T,
            ): T = create(configuration).use { block(it) }
        }
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) { tracked }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                assertEquals(0, pool.activeConnections)
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
                val version = query["versionId"]
                val key = "${exchange.requestURI.path}@$version"
                requests.add(key)
                signatures.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                val value = data[key]
                val status = if (exchange.requestURI.path == failRead) 403 else if (value == null) 404 else 200
                val response = if (status == 200) checkNotNull(value) else
                    "<Error><Code>${if (status == 404) "NoSuchVersion" else "AccessDenied"}</Code></Error>"
                if (status == 200) exchange.responseHeaders.add("x-amz-version-id", version)
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        fun source(bucket: String) = StorageConfiguration(
            "s3", "", bucket = bucket, region = "us-east-1", forcePathStyle = true,
            endpoint = "http://127.0.0.1:${server.address.port}", accessKeyId = "CAPTURE_CONFIG_KEY", accessKeySecret = "test-secret",
        )
        configurations = mapOf(
            "retained" to StorageConfiguration("fs", "", basePath = directory.resolve("retained").toString()),
            "source.a" to source("content"), "source.b" to source("artifacts"),
        )
        install()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        withConnectionManager { storage.bindConfigured(configurations.keys) }
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
    fun `configuration persists exact bound IDs without opening stores or changing the plan`(): Unit = runBlocking {
        val plan = prepare()
        val binding = configure(plan.key)
        assertEquals("retained", binding.backupKey)
        assertEquals(mapping, binding.stores)
        assertEquals(binding, withConnectionManager { service.getStorage(plan.key) })
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
        assertTrue(opened.isEmpty())
        assertTrue(requests.isEmpty())
        assertFalse(directory.resolve("retained").exists())
    }

    @Test
    fun `matching mapping replay preserves its original identity and creation time`(): Unit = runBlocking {
        val plan = prepare()
        val binding = configure(plan.key)
        assertEquals(binding, configure(plan.key, mapping.entries.reversed().associate { it.key to it.value }))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `concurrent matching configuration records one complete mapping`(): Unit = runBlocking {
        val plan = prepare()
        val results = (1..3).map { async { configure(plan.key) } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(results.first(), withConnectionManager { service.getStorage(plan.key) })
    }

    @Test
    fun `concurrent conflicting configuration keeps exactly one complete mapping`(): Unit = runBlocking {
        val plan = prepare()
        val alternatives = listOf(mapping, mapOf("content" to "source.a", "artifacts" to "source.a"))
        val results = alternatives.map { selected -> async {
            try { configure(plan.key, selected) } catch (_: IllegalArgumentException) { null }
        } }.awaitAll().filterNotNull()
        assertEquals(1, results.size)
        assertEquals(results.single(), withConnectionManager { service.getStorage(plan.key) })
    }

    @Test
    fun `changed or incomplete logical mappings cannot replace existing intent`(): Unit = runBlocking {
        val plan = prepare()
        val binding = configure(plan.key)
        for (invalid in listOf(mapping - "content", mapping + ("unknown" to "source.a"),
            mapping + ("artifacts" to "source.a"), mapping + ("content" to "retained"))) {
            assertFailsWith<IllegalArgumentException> { configure(plan.key, invalid) }
        }
        assertEquals(binding, withConnectionManager { service.getStorage(plan.key) })
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `missing blank or unbound storage fails atomically before mapping is recorded`(): Unit = runBlocking {
        val plan = prepare()
        for (id in listOf("", " ", "missing", "retained")) {
            assertFailsWith<IllegalArgumentException> { configure(plan.key, mapping + ("content" to id)) }
            assertNull(withConnectionManager { service.getStorage(plan.key) })
        }
        val unbound = prepare(selection.copy(id = "unbound"), destination = "unbound")
        assertFailsWith<IllegalArgumentException> { configure(unbound.key) }
        assertNull(withConnectionManager { service.getStorage(unbound.key) })
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `caller rollback removes the complete new mapping`(): Unit = runBlocking {
        val plan = prepare()
        assertFailsWith<IOException> {
            withConnectionManager {
                transaction {
                    service.configureStorage(plan.key, mapping)
                    throw IOException("rollback mapping")
                }
            }
        }
        assertNull(withConnectionManager { service.getStorage(plan.key) })
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
    }

    @Test
    fun `storage lookups and configuration validate plan keys`(): Unit = runBlocking {
        assertNull(withConnectionManager { service.getStorage("unknown") })
        assertFailsWith<IllegalArgumentException> { configure(" ") }
        assertFailsWith<IllegalStateException> { configure("unknown") }
        assertFailsWith<IllegalArgumentException> { withConnectionManager { service.getStorage("") } }
    }

    @Test
    fun `configured capture performs signed exact-version reads and closes every opened store`(): Unit = runBlocking {
        val plan = configured()
        val result = service.captureConfigured(plan.key, configurations, OffsetDateTime.MIN)
        assertEquals(2, result.objects.size)
        assertEquals(setOf("/content/asset@v1", "/artifacts/asset@v1"), requests.toSet())
        assertTrue(signatures.all { it.contains("Credential=CAPTURE_CONFIG_KEY/") })
        assertEquals(setOf("retained", "content", "artifacts"), opened.toSet())
        assertEquals(opened.sorted(), closed.sorted())
        assertEquals(result, load(plan.path))
    }

    @Test
    fun `application catalog uses literal saved IDs and ignores unrelated malformed entries`(): Unit = runBlocking {
        val plan = configured()
        install(catalog() + ("unused" to JsonPrimitive("invalid")))
        assertEquals(2, service.captureConfigured(plan.key, OffsetDateTime.MIN).objects.size)
        assertEquals(3, opened.size)
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `restart reuses captured checkpoints when sources are unavailable`(): Unit = runBlocking {
        val plan = configured()
        val first = service.captureConfigured(plan.key, OffsetDateTime.MIN)
        val binding = withConnectionManager { service.getStorage(plan.key) }
        data.clear()
        requests.clear()
        opened.clear()
        closed.clear()
        install()
        assertEquals(first, service.captureConfigured(plan.key, OffsetDateTime.MIN))
        assertTrue(requests.isEmpty())
        assertEquals(binding, withConnectionManager { service.getStorage(plan.key) })
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `later configured inventory copies only changed revisions`(): Unit = runBlocking {
        val first = configured()
        val before = service.captureConfigured(first.key, OffsetDateTime.MIN)
        data.clear()
        data["/content/asset@v2"] = "next"
        requests.clear()
        val next = configured(selection.copy(id = "later", stores = selection.stores + ("content" to listOf(entry("v2")))))
        val result = service.captureConfigured(next.key, OffsetDateTime.MIN)
        assertEquals(listOf("/content/asset@v2"), requests.toList())
        assertEquals(before.objects.first().payloadPath, result.objects.first().payloadPath)
        assertEquals(2, result.objects.size)
    }

    @Test
    fun `runtime credential rotation preserves bound addresses and persists no credentials`(): Unit = runBlocking {
        val plan = configured()
        val rotated = configurations.mapValues { (_, value) ->
            if (value.type == "s3") value.copy(accessKeyId = "ROTATED_KEY", accessKeySecret = "rotated-secret") else value
        }
        service.captureConfigured(plan.key, rotated, OffsetDateTime.MIN)
        assertTrue(signatures.all { it.contains("Credential=ROTATED_KEY/") })
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    select jsonb_build_object(
                        'bindings', (select jsonb_agg(to_jsonb(s)) from backup.object_stores s),
                        'capture', (select jsonb_agg(to_jsonb(s)) from backup.object_capture_storage s),
                        'stores', (select jsonb_agg(to_jsonb(s)) from backup.object_capture_stores s)
                    )::text
                """.trimIndent()).use {
                    assertTrue(it.next())
                    val saved = it.getString(1)
                    for (secret in listOf("CAPTURE_CONFIG_KEY", "test-secret", "ROTATED_KEY", "rotated-secret")) {
                        assertFalse(saved.contains(secret))
                    }
                }
            }
        }
    }

    @Test
    fun `changed retained or source address is rejected before opening any store`(): Unit = runBlocking {
        val plan = configured()
        val retained = configurations.getValue("retained").copy(basePath = directory.resolve("other").toString())
        val source = configurations.getValue("source.a").copy(bucket = "other")
        for (changed in listOf(configurations + ("retained" to retained), configurations + ("source.a" to source))) {
            assertFailsWith<IllegalArgumentException> { service.captureConfigured(plan.key, changed, null) }
        }
        assertTrue(opened.isEmpty())
        assertTrue(requests.isEmpty())
        assertEquals(mapping, withConnectionManager { assertNotNull(service.getStorage(plan.key)).stores })
    }

    @Test
    fun `missing and malformed selected configurations fail before opening stores`(): Unit = runBlocking {
        val plan = configured()
        assertFailsWith<IllegalArgumentException> { service.captureConfigured(plan.key, configurations - "source.a", null) }
        val invalid = configurations + ("source.a" to StorageConfiguration("unknown", ""))
        assertFailsWith<IllegalArgumentException> { service.captureConfigured(plan.key, invalid, null) }
        install(catalog() - "retained")
        assertFailsWith<IllegalArgumentException> { service.captureConfigured(plan.key, null) }
        install(catalog() + ("source.a" to JsonPrimitive("invalid")))
        assertFailsWith<SerializationException> { service.captureConfigured(plan.key, null) }
        assertTrue(opened.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `unconfigured and missing plans fail before catalog lookup or store opening`(): Unit = runBlocking {
        val plan = prepare()
        install(emptyMap())
        val failure = assertFailsWith<IllegalStateException> { service.captureConfigured(plan.key, null) }
        assertTrue(failure.message.orEmpty().contains("not configured"))
        assertFailsWith<IllegalStateException> { service.captureConfigured("unknown", null) }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `later open failure closes all earlier stores and leaves saved intent unchanged`(): Unit = runBlocking {
        val plan = configured()
        val binding = withConnectionManager { service.getStorage(plan.key) }
        failOpen = "artifacts"
        assertFailsWith<IOException> { service.captureConfigured(plan.key, null) }
        assertEquals(setOf("retained", "content"), opened.toSet())
        assertEquals(opened.sorted(), closed.sorted())
        assertEquals(binding, withConnectionManager { service.getStorage(plan.key) })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `capture failure closes stores and retry reads only unfinished revisions`(): Unit = runBlocking {
        val plan = configured()
        failRead = "/content/asset"
        assertFailsWith<Exception> { service.captureConfigured(plan.key, null) }
        assertEquals(opened.sorted(), closed.sorted())
        failRead = null
        requests.clear()
        opened.clear()
        closed.clear()
        assertEquals(2, service.captureConfigured(plan.key, null).objects.size)
        assertEquals(listOf("/content/asset@v1"), requests.toList())
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `cancellation closes stores and preserves completed captures for retry`(): Unit = runBlocking {
        val plan = configured()
        cancelRead = "content"
        val work = async { service.captureConfigured(plan.key, null) }
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        assertEquals(opened.sorted(), closed.sorted())
        assertEquals(mapping, withConnectionManager { assertNotNull(service.getStorage(plan.key)).stores })
        cancelRead = null
        requests.clear()
        opened.clear()
        closed.clear()
        assertEquals(2, service.captureConfigured(plan.key, null).objects.size)
        assertEquals(listOf("/content/asset@v1"), requests.toList())
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `active caller transaction is rejected before any configured storage access`(): Unit = runBlocking {
        val plan = configured()
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.captureConfigured(plan.key, configurations, null) } }
        }
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.captureConfigured(plan.key, null) } }
        }
        assertTrue(opened.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `empty selection binds only independent retained storage and publishes its explicit scope`(): Unit = runBlocking {
        val plan = configured(selection.copy(stores = emptyMap()), stores = emptyMap())
        val result = service.captureConfigured(plan.key, configurations + ("unused" to StorageConfiguration("bad", "")), null)
        assertTrue(result.objects.isEmpty())
        assertEquals(listOf("retained"), opened.toList())
        assertEquals(opened.toList(), closed.toList())
        assertEquals(result, load(plan.path))
    }

    @Test
    fun `logical stores may intentionally share a bound source namespace`(): Unit = runBlocking {
        val plan = configured(stores = mapOf("content" to "source.a", "artifacts" to "source.a"))
        val result = service.captureConfigured(plan.key, null)
        assertEquals(2, result.objects.size)
        assertEquals(setOf("retained", "content"), opened.toSet())
        assertEquals(2, requests.size)
        assertEquals(opened.sorted(), closed.sorted())
    }

    private fun entry(version: String = "v1") = ObjectStorageEntry("asset", ObjectStorageInfo(4, version = version))

    private suspend fun prepare(
        inventory: ObjectBackupCaptureInventory = selection,
        destination: String = "retained",
    ) = withConnectionManager {
        service.prepareInventory(inventory, destination, "payloads", "manifests/${inventory.id}.json")
    }

    private suspend fun configure(key: String, stores: Map<String, String> = mapping) = withConnectionManager {
        service.configureStorage(key, stores)
    }

    private suspend fun configured(
        inventory: ObjectBackupCaptureInventory = selection,
        stores: Map<String, String> = mapping,
    ) = prepare(inventory).also { configure(it.key, stores) }

    private fun catalog(): Map<String, JsonElement> = configurations.mapValues { (_, value) ->
        Json.encodeToJsonElement(StorageConfiguration.serializer(), value)
    }

    private suspend fun install(entries: Map<String, JsonElement> = catalog()) {
        if (::application.isInitialized) application.shutdown()
        val root = buildJsonObject { put("backup", buildJsonObject { put("objectStores", JsonObject(entries)) }) }
        application = BoscaApplication(ApplicationConfig.load(
            Json.encodeToString(JsonObject.serializer(), root).byteInputStream(),
        ), ownsServices = false)
        provides<BoscaApplication>(singleton = true, overrideExisting = true) { application }
        BackupProviderRegistrar().register()
        service = provide()
        storage = provide()
    }

    private fun load(path: String): ObjectBackupManifest = Json.decodeFromString(
        ObjectBackupManifest.serializer(), directory.resolve("retained").resolve(path).toFile().readText(),
    )
}
