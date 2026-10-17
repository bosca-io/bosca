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
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises durable address claims through generated DI and actual filesystem/S3 storage. */
@OptIn(InternalDI::class)
class ObjectBackupStorageIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var service: ObjectBackupStorageService

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_storage").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-storage-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        directory = createTempDirectory("bosca-backup-storage-")
        service = provide()
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
    fun `filesystem address survives restart without creating its directory`(): Unit = runBlocking {
        val configuration = filesystem("retained")
        val bound = request { service.bind("backup", configuration) }
        assertEquals("fs", bound.type)
        assertEquals(directory.resolve("retained").toFile().canonicalPath, bound.basePath)
        assertNull(bound.endpoint)
        assertNull(bound.bucket)
        assertNull(bound.region)
        assertEquals(false, directory.resolve("retained").exists())
        BackupProviderRegistrar().register()
        service = provide()
        assertEquals(bound, request { service.get("backup") })
        assertEquals(bound, request { service.bind("backup", configuration) })
        assertNull(request { service.get("missing") })
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        assertEquals(bound, request { service.get("backup") })
    }

    @Test
    fun `one identity cannot be redirected to another location or backend`(): Unit = runBlocking {
        val bound = request { service.bind("backup", filesystem("first")) }
        for (configuration in listOf(filesystem("second"), s3())) {
            assertFailsWith<IllegalArgumentException> { request { service.bind("backup", configuration) } }
        }
        assertEquals(bound, request { service.get("backup") })
    }

    @Test
    fun `canonical filesystem aliases cannot use different identities`(): Unit = runBlocking {
        val real = directory.resolve("real").createDirectories()
        val alias = directory.resolve("alias").createSymbolicLinkPointingTo(real)
        val bound = request { service.bind("backup", filesystem("real/future")) }
        assertEquals(bound, request { service.bind("backup", filesystem("alias/future")) })
        assertFailsWith<IllegalArgumentException> {
            request { service.bind("other", filesystem("alias/future/../future")) }
        }
        assertNull(request { service.get("other") })
        assertEquals(true, alias.exists())
    }

    @Test
    fun `retargeting a filesystem symlink cannot redirect an existing identity`(): Unit = runBlocking {
        val first = directory.resolve("first").createDirectories()
        val second = directory.resolve("second").createDirectories()
        val alias = directory.resolve("alias").createSymbolicLinkPointingTo(first)
        val bound = request { service.bind("backup", filesystem("alias")) }
        java.nio.file.Files.delete(alias)
        alias.createSymbolicLinkPointingTo(second)
        assertFailsWith<IllegalArgumentException> { request { service.bind("backup", filesystem("alias")) } }
        assertEquals(bound, request { service.get("backup") })
    }

    @Test
    fun `concurrent different locations cannot claim one identity`(): Unit = runBlocking {
        val results = listOf(filesystem("first"), filesystem("second")).map { configuration ->
            async {
                try { request { service.bind("backup", configuration) } } catch (_: IllegalArgumentException) { null }
            }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(results.filterNotNull().single(), request { service.get("backup") })
    }

    @Test
    fun `concurrent identities cannot claim the same location`(): Unit = runBlocking {
        val results = listOf("first", "second").map { key ->
            async {
                try { request { service.bind(key, filesystem("shared")) } } catch (_: IllegalArgumentException) { null }
            }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(1, request { listOf(service.get("first"), service.get("second")).count { it != null } })
    }

    @Test
    fun `concurrent matching claims return the same immutable record`(): Unit = runBlocking {
        val results = List(2) { async { request { service.bind("backup", filesystem("shared")) } } }.awaitAll()
        assertEquals(results[0], results[1])
    }

    @Test
    fun `S3 address excludes credentials presentation and bucket creation options`(): Unit = runBlocking {
        val configuration = s3()
        val bound = request { service.bind("backup", configuration) }
        assertEquals("s3", bound.type)
        assertEquals(configuration.endpoint, bound.endpoint)
        assertEquals("retained", bound.bucket)
        assertEquals("us-east-1", bound.region)
        assertNull(bound.basePath)
        assertEquals(bound, request {
            service.bind("backup", configuration.copy(
                accessKeyId = "ROTATED_KEY", accessKeySecret = "rotated-secret",
                urlPrefix = "https://presentation.example", urlUploadPrefix = "https://uploads.example",
                createBucket = true, forcePathStyle = false,
            ))
        })
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select to_jsonb(s)::text from backup.object_stores s").use { rows ->
                    assertTrue(rows.next())
                    val stored = rows.getString(1)
                    for (excluded in listOf("TEST_KEY", "test-secret", "ROTATED_KEY", "rotated-secret", "presentation", "uploads")) {
                        assertEquals(false, stored.contains(excluded))
                    }
                }
            }
        }
    }

    @Test
    fun `S3 endpoint bucket and region cannot change under a bound identity`(): Unit = runBlocking {
        val configuration = s3()
        val bound = request { service.bind("backup", configuration) }
        for (changed in listOf(
            configuration.copy(endpoint = "https://different.example"),
            configuration.copy(bucket = "different"),
            configuration.copy(region = "us-west-2"),
        )) {
            assertFailsWith<IllegalArgumentException> { request { service.bind("backup", changed) } }
        }
        assertEquals(bound, request { service.get("backup") })
        assertFailsWith<IllegalArgumentException> {
            request { service.bind("other", configuration.copy(region = "us-west-2")) }
        }
        assertNull(request { service.get("other") })
    }

    @Test
    fun `equivalent S3 endpoint spellings share one address`(): Unit = runBlocking {
        val bound = request { service.bind("backup", s3().copy(endpoint = "HTTPS://STORE.EXAMPLE:443/")) }
        assertEquals("https://store.example", bound.endpoint)
        assertEquals(bound, request { service.bind("backup", s3()) })
        assertFailsWith<IllegalArgumentException> { request { service.bind("other", s3()) } }
        assertNull(request { service.get("other") })
    }

    @Test
    fun `invalid configuration cannot leave a binding or invoke work`(): Unit = runBlocking {
        for (configuration in listOf(
            StorageConfiguration("noop", ""), StorageConfiguration("unsupported", ""),
            StorageConfiguration("fs", ""), StorageConfiguration("fs", "", basePath = " "),
            s3().copy(bucket = null), s3().copy(bucket = " "),
            s3().copy(region = null), s3().copy(region = " "),
            s3().copy(endpoint = "https://user:secret@store.example"),
            s3().copy(endpoint = "https://store.example?credential=secret"),
            s3().copy(endpoint = "https://store.example#secret"),
            s3().copy(endpoint = "ftp://store.example"),
            s3().copy(endpoint = "https://store.example:99999"),
            s3().copy(endpoint = "not a URI"),
        )) {
            var invoked = false
            assertFailsWith<IllegalArgumentException> {
                service.withStorage("invalid", configuration) { invoked = true }
            }
            assertEquals(false, invoked)
            assertNull(request { service.get("invalid") })
        }
        assertFailsWith<IllegalArgumentException> { request { service.bind(" ", filesystem("valid")) } }
        assertFailsWith<IllegalArgumentException> { request { service.get("") } }
    }

    @Test
    fun `a missing S3 endpoint cannot inherit an unrecorded SDK endpoint`(): Unit = runBlocking {
        if (System.getenv("AWS_ENDPOINT") != null) {
            val bound = request { service.bind("backup", s3().copy(endpoint = null)) }
            assertNotNull(bound.endpoint)
        } else {
            assertFailsWith<IllegalArgumentException> { request { service.bind("backup", s3().copy(endpoint = null)) } }
            assertNull(request { service.get("backup") })
        }
    }

    @Test
    fun `an existing regular file cannot be bound as a filesystem store`(): Unit = runBlocking {
        directory.resolve("file").toFile().writeText("not a directory")
        var invoked = false
        assertFailsWith<IllegalArgumentException> {
            service.withStorage("backup", filesystem("file")) { invoked = true }
        }
        assertEquals(false, invoked)
        assertNull(request { service.get("backup") })
    }

    @Test
    fun `binding participates in caller rollback`(): Unit = runBlocking {
        assertFailsWith<IOException> {
            request {
                transaction {
                    service.bind("rolled-back", filesystem("retained"))
                    throw IOException("abort preparation")
                }
            }
        }
        assertNull(request { service.get("rolled-back") })
        assertNotNull(request { service.bind("replacement", filesystem("retained")) })
    }

    @Test
    fun `scoped opening cannot perform external work inside a caller transaction`(): Unit = runBlocking {
        var invoked = false
        assertFailsWith<IllegalStateException> {
            request {
                transaction {
                    service.withStorage("backup", filesystem("retained")) { invoked = true }
                }
            }
        }
        assertEquals(false, invoked)
        assertNull(request { service.get("backup") })
        request {
            service.withStorage("backup", filesystem("retained")) {
                assertEquals(0, pool.activeConnections)
                write(it, "asset", "data")
            }
        }
        assertEquals("data", directory.resolve("retained/asset").toFile().readText())
    }

    @Test
    fun `scoped stores use their recorded locations without holding database connections`(): Unit = runBlocking {
        val result = service.withStorage("source", filesystem("source")) { source ->
            assertEquals(0, pool.activeConnections)
            write(source, "asset", "source")
            service.withStorage("destination", filesystem("destination")) { destination ->
                assertEquals(0, pool.activeConnections)
                source.getInputStream(StringObjectPath("asset")).use { destination.setInputStream(StringObjectPath("asset"), it, 6) }
                destination.getString(StringObjectPath("asset"))
            }
        }
        assertEquals("source", result)
        assertEquals("source", directory.resolve("destination/asset").toFile().readText())
        var invoked = false
        assertFailsWith<IllegalArgumentException> {
            service.withStorage("destination", filesystem("different")) { invoked = true }
        }
        assertEquals(false, invoked)
        assertEquals(false, directory.resolve("different").exists())
    }

    @Test
    fun `scoped opening uses the captured path when its original symlink changes`(): Unit = runBlocking {
        val first = directory.resolve("first").createDirectories()
        val second = directory.resolve("second").createDirectories()
        val alias = directory.resolve("alias").createSymbolicLinkPointingTo(first)
        val actualFactory = provide<ObjectStorageServiceFactory>()
        val factory = object : ObjectStorageServiceFactory by actualFactory {
            override suspend fun <T> withStorage(
                configuration: StorageConfiguration,
                block: suspend (ObjectStorageService) -> T,
            ): T {
                assertEquals(0, pool.activeConnections)
                assertEquals(first.toFile().canonicalPath, configuration.basePath)
                java.nio.file.Files.delete(alias)
                alias.createSymbolicLinkPointingTo(second)
                return actualFactory.withStorage(configuration, block)
            }
        }
        val selectedService = ObjectBackupStorageServiceImpl(provide(), factory)
        selectedService.withStorage("backup", filesystem("alias")) { write(it, "asset", "data") }
        assertEquals("data", first.resolve("asset").toFile().readText())
        assertEquals(false, second.resolve("asset").exists())
        assertEquals(first.toFile().canonicalPath, request { service.get("backup") }?.basePath)
    }

    @Test
    fun `S3 scoped clients use rotated credentials and close after work failure or cancellation`(): Unit = runBlocking {
        val authorizations = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                if (exchange.requestMethod != "GET" || exchange.requestURI.path != "/retained/asset") {
                    exchange.sendResponseHeaders(404, -1)
                    return@use
                }
                authorizations.add(exchange.requestHeaders.getFirst("Authorization"))
                val bytes = "data".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            val configuration = s3().copy(endpoint = "http://127.0.0.1:${server.address.port}")
            for (failure in listOf(IOException("work failed"), CancellationException("work cancelled"))) {
                lateinit var captured: ObjectStorageService
                val selected = if (failure is CancellationException) configuration.copy(accessKeyId = "ROTATED_KEY") else configuration
                val thrown = assertFailsWith<Exception> {
                    service.withStorage("backup", selected) {
                        assertEquals(0, pool.activeConnections)
                        captured = it
                        assertEquals("data", it.getString(StringObjectPath("asset")))
                        throw failure
                    }
                }
                assertTrue(generateSequence(thrown as Throwable) { it.cause }.any { it === failure })
                val count = authorizations.size
                assertFailsWith<Exception> { captured.getString(StringObjectPath("asset")) }
                assertEquals(count, authorizations.size)
            }
            assertTrue(authorizations[0].contains("Credential=TEST_KEY/"))
            assertTrue(authorizations[1].contains("Credential=ROTATED_KEY/"))
            assertEquals(configuration.endpoint, request { service.get("backup") }?.endpoint)
        } finally {
            server.stop(0)
        }
    }

    private fun filesystem(name: String) = StorageConfiguration("fs", "", basePath = directory.resolve(name).toString())

    private fun s3() = StorageConfiguration(
        "s3", "", endpoint = "https://store.example", bucket = "retained", region = "us-east-1",
        accessKeyId = "TEST_KEY", accessKeySecret = "test-secret", forcePathStyle = true,
    )

    private suspend fun <T> request(block: suspend () -> T): T = withConnectionManager(block)

    private suspend fun write(storage: ObjectStorageService, key: String, value: String) {
        val bytes = value.toByteArray()
        storage.setInputStream(StringObjectPath(key), bytes.inputStream(), bytes.size.toLong())
    }
}
