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
import bosca.storage.service.FileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import io.mockk.coVerify
import io.mockk.mockk
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Generated services, real signed SDK reads, independent retained storage and PostgreSQL restore. */
@OptIn(InternalDI::class)
class ObjectBackupCaptureIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var retained: ObjectStorageService
    private lateinit var capture: ObjectBackupCaptureService
    private lateinit var endpoint: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val authorizations = CopyOnWriteArrayList<String>()
    private val content = "retained source content"
    private val version = "retained/+% =version"
    private val selected = ObjectStorageEntry("asset", ObjectStorageInfo(content.toByteArray().size.toLong(), version = version))
    private val payloadPath = "payloads/capture-1/asset"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_capture").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-capture-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        directory = createTempDirectory("bosca-capture-integration-")
        retained = filesystem("retained")
        capture = provide()
        endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        endpoint.createContext("/") { exchange ->
            exchange.use {
                assertEquals(0, pool.activeConnections)
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
                requests.add("${exchange.requestMethod} ${exchange.requestURI.path} ${query["versionId"]}")
                authorizations.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                if (exchange.requestMethod == "GET" && exchange.requestURI.path == "/bucket/asset" && query["versionId"] == version) {
                    val bytes = content.toByteArray()
                    exchange.responseHeaders.add("x-amz-version-id", version)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        }
        endpoint.start()
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::endpoint.isInitialized) endpoint.stop(0)
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `captures publishes and restores retained content after the source is offline`(): Unit = runBlocking {
        assertSame(capture, provide<ObjectBackupCaptureService>())
        val result = provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            capture.capture("content", selected, source, retained, payloadPath)
        }
        assertEquals(listOf("GET /bucket/asset $version"), requests.toList())
        assertTrue(authorizations.single().contains("Credential=CAPTURE_KEY/"))
        endpoint.stop(0)

        val inventory = ObjectBackupManifest("capture-1", "old-instance", "content", "2026-10-17T00:00:00Z", listOf(result))
        val path = StringObjectPath("manifests/capture-1.json")
        ObjectBackupManifestStore(retained, Json).save(path, inventory)
        assertEquals(inventory, ObjectBackupManifestStore(retained, Json).load(path))
        val destination = filesystem("restored")
        val restore = provide<ObjectBackupRestoreService>()
        assertEquals(inventory, restore.restore(path, retained, mapOf("content" to destination), "new-instance"))
        assertEquals(content, destination.getString(StringObjectPath("asset")))
        val progress = withConnectionManager {
            provide<ObjectBackupProgressService>().get(
                ObjectBackupOperation.RESTORE, "old-instance", "new-instance", "content", result.copy(payloadPath = result.key),
            )
        }
        assertEquals(ObjectBackupProgressStatus.COMPLETED, progress?.status)
        assertEquals(listOf("GET /bucket/asset $version"), requests.toList())
    }

    @Test
    fun `an active caller transaction is rejected before storage lookup or payload reads`(): Unit = runBlocking {
        val source = mockk<ObjectStorageService>()
        val target = mockk<ObjectStorageService>()
        assertFailsWith<IllegalStateException> {
            withConnectionManager {
                transaction { capture.capture("content", selected, source, target, payloadPath) }
            }
        }
        coVerify(exactly = 0) { target.getObjectInfo(any()) }
        coVerify(exactly = 0) { source.getInputStream(any(), any<String>()) }
    }

    @Test
    fun `capture keeps database connections free while opening writing and verifying payloads`(): Unit = runBlocking {
        val target = object : ObjectStorageService by retained {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                assertEquals(0, pool.activeConnections)
                return retained.getObjectInfo(path)
            }
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                assertEquals(0, pool.activeConnections)
                return retained.setInputStream(path, stream, length)
            }
            override suspend fun getInputStream(path: ObjectPath): InputStream {
                assertEquals(0, pool.activeConnections)
                return retained.getInputStream(path)
            }
        }
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            capture.capture("content", selected, source, target, payloadPath)
        }
        assertEquals(0, pool.activeConnections)
        assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
    }

    @Test
    fun `actual coroutine cancellation during verification removes the unacknowledged capture`(): Unit = runBlocking {
        val target = object : ObjectStorageService by retained {
            override suspend fun getInputStream(path: ObjectPath): InputStream {
                currentCoroutineContext().cancel(CancellationException("capture verification cancelled"))
                return retained.getInputStream(path)
            }
        }
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            val work = async { capture.capture("content", selected, source, target, payloadPath) }
            assertFailsWith<CancellationException> { work.await() }
            work.join()
        }
        assertFailsWith<ObjectNotFoundException> { retained.getObjectInfo(StringObjectPath(payloadPath)) }
    }

    private fun configuration() = StorageConfiguration(
        "s3", "", bucket = "bucket", forcePathStyle = true, region = "us-east-1",
        endpoint = "http://127.0.0.1:${endpoint.address.port}",
        accessKeyId = "CAPTURE_KEY", accessKeySecret = "test-secret",
    )

    private fun filesystem(name: String) =
        FileSystemObjectStorageService("", "", mockk(), directory.resolve(name).toString(), mockk())
}
