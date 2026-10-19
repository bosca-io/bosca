package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.connectionOrNull
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
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Generated services and real PostgreSQL/S3 SDK requests, with independent retained files. */
@OptIn(InternalDI::class)
class ObjectBackupDiscoveryIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var server: HttpServer
    private lateinit var directory: Path
    private lateinit var application: BoscaApplication
    private lateinit var service: ObjectBackupCaptureService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private val current = ConcurrentHashMap<String, String>()
    private val retained = ConcurrentHashMap<String, String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()
    private val closed = CopyOnWriteArrayList<String>()
    private var failKey: String? = null
    private var cancelKey: String? = null
    private var unversionedKey: String? = null
    private var checkPool = true

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_discovery").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4),
            key = "discovery-test",
        ))
        directory = createTempDirectory("bosca-discovery-")
        put("media/a", "v1", "alpha")
        put("media/b", "v1", "bravo")
        put("excluded", "v1", "excluded")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                if (checkPool) assertEquals(0, pool.activeConnections)
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
                val key = exchange.requestURI.path.removePrefix("/source/")
                requests.add("${exchange.requestMethod} $key${exchange.requestURI.rawQuery?.let { "?$it" }.orEmpty()}")
                when {
                    query["list-type"] == "2" -> {
                        val keys = current.keys.filter { it.startsWith(query["prefix"].orEmpty()) }.sorted()
                        val offset = query["continuation-token"]?.toInt() ?: 0
                        val page = keys.drop(offset).take(query["max-keys"]?.toInt() ?: 1000)
                        val next = offset + page.size
                        val xml = "<ListBucketResult><IsTruncated>${next < keys.size}</IsTruncated>" +
                            (if (next < keys.size) "<NextContinuationToken>$next</NextContinuationToken>" else "") +
                            page.joinToString("") { path ->
                                "<Contents><Key>$path</Key><Size>${retained.getValue("$path@${current.getValue(path)}").length}</Size></Contents>"
                            } + "</ListBucketResult>"
                        val bytes = xml.toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                    key == failKey -> exchange.sendResponseHeaders(403, -1)
                    else -> {
                        val version = query["versionId"] ?: current[key]
                        val value = retained["$key@$version"]
                        if (value == null) {
                            exchange.sendResponseHeaders(404, -1)
                        } else {
                            if (key != unversionedKey) exchange.responseHeaders.add("x-amz-version-id", version)
                            exchange.responseHeaders.add("Content-Length", value.toByteArray().size.toString())
                            if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
                            else {
                                val bytes = value.toByteArray()
                                exchange.sendResponseHeaders(200, bytes.size.toLong())
                                exchange.responseBody.write(bytes)
                            }
                        }
                    }
                }
            }
        }
        server.start()
        configurations = mapOf(
            "source.id" to StorageConfiguration(
                "s3", "", bucket = "source", region = "us-east-1", forcePathStyle = true,
                endpoint = "http://127.0.0.1:${server.address.port}", accessKeyId = "TEST_KEY", accessKeySecret = "test-secret",
            ),
            "retained" to StorageConfiguration("fs", "", basePath = directory.resolve("retained").toString()),
        )
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        val factory = provide<ObjectStorageServiceFactory>()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by factory {
                override suspend fun create(configuration: StorageConfiguration): ObjectStorageService {
                    assertNull(connectionOrNull())
                    if (checkPool) assertEquals(0, pool.activeConnections)
                    val actual = factory.create(configuration)
                    val identity = configuration.bucket ?: "retained"
                    opened.add(identity)
                    return object : ObjectStorageService by actual {
                        override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                            assertNull(connectionOrNull())
                            if (path.toString() == cancelKey) throw CancellationException("discovery cancelled")
                            return actual.getObjectInfo(path)
                        }
                        override fun close() {
                            if (checkPool) assertEquals(0, pool.activeConnections)
                            closed.add(identity)
                            actual.close()
                        }
                    }
                }
                override suspend fun <T> withStorage(
                    configuration: StorageConfiguration, block: suspend (ObjectStorageService) -> T,
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
    fun `discovery saves exact scoped revisions and mapping without reading asset bodies`(): Unit = runBlocking {
        val plan = service.discoverConfigured(request())
        assertEquals(listOf("media/a", "media/b"), plan.inventory.stores.getValue("content").map { it.key })
        assertEquals(listOf("v1", "v1"), plan.inventory.stores.getValue("content").map { it.info.version })
        assertEquals(mapOf("content" to "source.id"), withConnectionManager { assertNotNull(service.getStorage(plan.key)).stores })
        assertTrue(requests.any { it.startsWith("HEAD media/a") })
        assertTrue(requests.none { it.startsWith("GET media/") })
        assertEquals(opened.sorted(), closed.sorted())
        assertEquals(listOf(plan), plans())
    }

    @Test
    fun `restart replays immutable selection without source catalog or storage access`(): Unit = runBlocking {
        val first = service.discoverConfigured(request())
        put("media/a", "v2", "new")
        requests.clear(); opened.clear(); closed.clear()
        install(emptyMap())
        assertEquals(first, service.discoverConfigured(request()))
        assertTrue(requests.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `changed discovery intent cannot replace a completed selection`(): Unit = runBlocking {
        val selected = service.discoverConfigured(request())
        requests.clear()
        for (changed in listOf(
            request().copy(stores = mapOf("content" to ObjectBackupSource("source.id", "other/"))),
            request().copy(payloadPrefix = "other"), request().copy(manifestPath = "other.json"),
            request().copy(stores = mapOf("content" to ObjectBackupSource("different", "media/"))),
        )) assertFailsWith<IllegalArgumentException> { service.discoverConfigured(changed) }
        assertEquals(listOf(selected), plans())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `repeated live backups transfer only new and changed source revisions`(): Unit = runBlocking {
        val first = service.discoverConfigured(request())
        val before = service.captureConfigured(first.key, OffsetDateTime.MIN)
        requests.clear()
        val unchanged = service.discoverConfigured(request("unchanged"))
        val same = service.captureConfigured(unchanged.key, OffsetDateTime.MIN)
        assertEquals(before.objects, same.objects)
        assertTrue(requests.none { it.startsWith("GET media/") })
        put("media/b", "v2", "changed")
        put("media/c", "v1", "added")
        requests.clear()
        val later = service.discoverConfigured(request("later"))
        val after = service.captureConfigured(later.key, OffsetDateTime.MIN)
        assertEquals(listOf("GET media/b?versionId=v2", "GET media/c?versionId=v1"), requests.filter { it.startsWith("GET media/") })
        assertEquals(before.objects.first(), after.objects.first())
        assertEquals(3, after.objects.size)
        assertTrue(directory.resolve("retained/manifests/first.json").toFile().exists())
    }

    @Test
    fun `saved selection reads the old retained revision after current content changes`(): Unit = runBlocking {
        val plan = service.discoverConfigured(request())
        put("media/a", "v2", "replacement")
        current.remove("media/b")
        requests.clear()
        val result = service.captureConfigured(plan.key, null)
        assertEquals(setOf("v1"), result.objects.map { it.revision }.toSet())
        assertEquals(listOf("GET media/a?versionId=v1", "GET media/b?versionId=v1"), requests.filter { it.startsWith("GET media/") })
    }

    @Test
    fun `partial discovery failure publishes no plan and retry performs a fresh observation`(): Unit = runBlocking {
        failKey = "media/b"
        assertFailsWith<Exception> { service.discoverConfigured(request()) }
        assertTrue(plans().isEmpty())
        assertEquals(opened.sorted(), closed.sorted())
        failKey = null
        put("media/a", "v2", "new")
        val plan = service.discoverConfigured(request())
        assertEquals("v2", plan.inventory.stores.getValue("content").first().info.version)
    }

    @Test
    fun `sources without retained provider versions fail instead of claiming a usable selection`(): Unit = runBlocking {
        unversionedKey = "media/b"
        assertFailsWith<IllegalArgumentException> { service.discoverConfigured(request()) }
        assertTrue(plans().isEmpty())
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `cancellation closes source stores and does not save partial selection`(): Unit = runBlocking {
        cancelKey = "media/b"
        assertFailsWith<CancellationException> { service.discoverConfigured(request()) }
        assertTrue(plans().isEmpty())
        assertEquals(opened.sorted(), closed.sorted())
    }

    @Test
    fun `empty observed scope still saves a usable explicit manifest`(): Unit = runBlocking {
        current.clear()
        val plan = service.discoverConfigured(request())
        assertTrue(plan.inventory.stores.getValue("content").isEmpty())
        assertTrue(service.captureConfigured(plan.key, null).objects.isEmpty())
    }

    @Test
    fun `discovery scans more than one offset page before saving`(): Unit = runBlocking {
        current.clear()
        for (i in 0..104) put("media/${i.toString().padStart(3, '0')}", "v1", "data")
        val plan = service.discoverConfigured(request())
        assertEquals(105, plan.inventory.stores.getValue("content").size)
        assertEquals(105, requests.count { it.startsWith("HEAD media/") })
        assertTrue(requests.none { it.startsWith("GET media/") })
    }

    @Test
    fun `independent concurrent callers persist exactly one complete selection`(): Unit = runBlocking {
        checkPool = false
        val results = (1..3).map { async { service.discoverConfigured(request()) } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(results.distinct(), plans())
        assertNotNull(withConnectionManager { service.getStorage(results.first().key) })
    }

    @Test
    fun `invalid scope paths identities and destination aliases fail before storage access`(): Unit = runBlocking {
        for (invalid in listOf(
            request().copy(id = ""), request().copy(sourceId = " "), request().copy(scope = ""),
            request().copy(stores = emptyMap()), request().copy(stores = mapOf("" to ObjectBackupSource("source.id"))),
            request().copy(stores = mapOf("content" to ObjectBackupSource("source.id", "../bad"))),
            request().copy(destinationId = "source.id"), request().copy(payloadPrefix = "../bad"),
        )) assertFailsWith<IllegalArgumentException> { service.discoverConfigured(invalid) }
        assertTrue(opened.isEmpty())
        assertTrue(plans().isEmpty())
    }

    @Test
    fun `caller transaction is rejected before binding or opening storage`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.discoverConfigured(request()) } }
        }
        assertTrue(opened.isEmpty())
        assertTrue(plans().isEmpty())
    }

    private fun request(id: String = "first") = ObjectBackupDiscoveryRequest(
        id, "source-instance", "content", "retained", mapOf("content" to ObjectBackupSource("source.id", "media/")),
        "payloads", "manifests/$id.json",
    )

    private fun put(key: String, version: String, value: String) {
        retained["$key@$version"] = value
        current[key] = version
    }

    private suspend fun plans() = withConnectionManager { service.listPlans("source-instance", "retained", "content", 0, 100) }

    private suspend fun install(entries: Map<String, StorageConfiguration> = configurations) {
        if (::application.isInitialized) application.shutdown()
        val catalog = JsonObject(entries.mapValues { (_, value) -> Json.encodeToJsonElement(StorageConfiguration.serializer(), value) })
        val config = buildJsonObject { put("backup", buildJsonObject { put("objectStores", catalog) }) }
        application = BoscaApplication(ApplicationConfig.load(config.toString().byteInputStream()), ownsServices = false)
        provides<BoscaApplication>(singleton = true, overrideExisting = true) { application }
        BackupProviderRegistrar().register()
        service = provide()
    }
}
