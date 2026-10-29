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
import bosca.storage.service.ManagedFileSystemObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
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
    private lateinit var retained: ManagedObjectStorageService
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
        provides<Json>(singleton = true) { backupTestJson }
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
                authorizations.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                if (exchange.requestURI.path == "/bucket/asset" && query["versionId"] == null) {
                    val bytes = content.toByteArray()
                    exchange.responseHeaders.add("x-amz-version-id", version)
                    exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                    if (exchange.requestMethod == "HEAD") {
                        exchange.sendResponseHeaders(200, -1)
                    } else {
                        requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
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
        assertEquals(listOf("GET /bucket/asset"), requests.toList())
        assertTrue(authorizations.all { it.contains("Credential=CAPTURE_KEY/") })
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
        assertEquals(listOf("GET /bucket/asset"), requests.toList())
    }

    @Test
    fun `an active caller transaction is rejected before storage lookup or payload reads`(): Unit = runBlocking {
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val target = mockk<ManagedObjectStorageService>()
        assertFailsWith<IllegalStateException> {
            withConnectionManager {
                transaction { capture.capture("content", selected, source, target, payloadPath) }
            }
        }
        coVerify(exactly = 0) { target.getObjectInfo(any()) }
    }

    @Test
    fun `capture keeps database connections free while opening writing and verifying payloads`(): Unit = runBlocking {
        val target = object : ManagedObjectStorageService by retained {
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
        val target = object : ManagedObjectStorageService by retained {
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

    @Test
    fun `tracked capture records verified bytes and completion atomically`(): Unit = runBlocking {
        val result = provide<ObjectStorageServiceFactory>().withStorage(configuration()) { tracked(it) }
        assertEquals(ObjectBackupProgressStatus.COMPLETED, result.status)
        assertEquals(1, result.attempts)
        assertEquals(2L, result.version)
        assertEquals(knownPayload(), result.payload)
        assertEquals(result, checkpoint())
        assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
        assertEquals(1, requests.size)
    }

    @Test
    fun `tracked capture survives service restart and reuses the recorded path with source offline`(): Unit = runBlocking {
        val first = provide<ObjectStorageServiceFactory>().withStorage(configuration()) { tracked(it) }
        endpoint.stop(0)
        val proposed = "payloads/another-attempt"
        writeRetained(proposed, "unrelated")
        BackupProviderRegistrar().register()
        capture = provide()
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        assertEquals(first, tracked(source, path = proposed))
        assertEquals("unrelated", retained.getString(StringObjectPath(proposed)))
        assertEquals(first, checkpoint())
        coVerify(exactly = 0) { source.getInputStream(any()) }
    }

    @Test
    fun `missing completed capture is invalidated and repaired from its retained provider revision`(): Unit = runBlocking {
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            val first = tracked(source)
            retained.delete(StringObjectPath(payloadPath))
            val repaired = tracked(source, path = "payloads/unused-proposal")
            assertEquals(first.payload, repaired.payload)
            assertEquals(ObjectBackupProgressStatus.COMPLETED, repaired.status)
            assertEquals(2, repaired.attempts)
            assertEquals(5L, repaired.version)
            assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `corrupt completed capture is repaired against the recorded digest`(): Unit = runBlocking {
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            val first = tracked(source)
            writeRetained(payloadPath, "x".repeat(content.length))
            val repaired = tracked(source)
            assertEquals(first.payload, repaired.payload)
            assertEquals(2, repaired.attempts)
            assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
        }
    }

    @Test
    fun `running captured revision is never taken over or probed`(): Unit = runBlocking {
        val running = withConnectionManager {
            val prepared = prepareKnown()
            provide<ObjectBackupProgressService>().start(prepared.key, prepared.version)
        }
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val target = mockk<ManagedObjectStorageService>()
        assertFailsWith<IllegalStateException> { tracked(source, target) }
        assertEquals(running, checkpoint())
        coVerify(exactly = 0) { target.getInputStream(any()) }
        coVerify(exactly = 0) { target.getObjectInfo(any()) }
    }

    @Test
    fun `pending and failed known revisions resume using the existing checkpoint`(): Unit = runBlocking {
        val prepared = withConnectionManager { prepareKnown() }
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            val first = tracked(source)
            assertEquals(prepared.key, first.key)
            retained.delete(StringObjectPath(payloadPath))
            withConnectionManager { provide<ObjectBackupProgressService>().invalidate(first.key, first.version, "removed") }
            val retried = tracked(source)
            assertEquals(first.key, retried.key)
            assertEquals(2, retried.attempts)
            assertEquals(ObjectBackupProgressStatus.COMPLETED, retried.status)
        }
    }

    @Test
    fun `pending matching output reconciles a lost acknowledgement without source access`(): Unit = runBlocking {
        withConnectionManager { prepareKnown() }
        writeRetained(payloadPath, content)
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val result = tracked(source)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, result.status)
        assertEquals(1, result.attempts)
    }

    @Test
    fun `a conflicting selected length cannot alter known revision intent`(): Unit = runBlocking {
        val prepared = withConnectionManager { prepareKnown() }
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val target = mockk<ManagedObjectStorageService>()
        assertFailsWith<IllegalArgumentException> {
            tracked(source, target, entry = selected.copy(info = selected.info.copy(length = selected.info.length + 1)))
        }
        assertEquals(prepared, checkpoint())
        coVerify(exactly = 0) { target.getInputStream(any()) }
    }

    @Test
    fun `known source failure remains a failed checkpoint and preserves its original cause`(): Unit = runBlocking {
        withConnectionManager { prepareKnown() }
        val failure = IOException("retained source denied")
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        coEvery { source.getInputStream(any()) } throws failure
        val observed = assertFailsWith<IOException> { tracked(source) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        val failed = checkpoint()
        assertEquals(ObjectBackupProgressStatus.FAILED, failed?.status)
        assertEquals(1, failed?.attempts)
        assertEquals(failure.message, failed?.error)
        coVerify(exactly = 1) { source.getInputStream(any()) }
    }

    @Test
    fun `known capture cancellation records failed work that can resume`(): Unit = runBlocking {
        withConnectionManager { prepareKnown() }
        val cancellation = CancellationException("capture cancelled")
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        coEvery { source.getInputStream(any()) } throws cancellation
        assertFailsWith<CancellationException> { tracked(source) }
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoint()?.status)
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { realSource ->
            val resumed = tracked(realSource)
            assertEquals(ObjectBackupProgressStatus.COMPLETED, resumed.status)
            assertEquals(2, resumed.attempts)
        }
    }

    @Test
    fun `unavailable completed output cannot be invalidated as though it were corrupt`(): Unit = runBlocking {
        val first = provide<ObjectStorageServiceFactory>().withStorage(configuration()) { tracked(it) }
        val failure = IOException("retained storage offline")
        val target = object : ManagedObjectStorageService by retained {
            override suspend fun getInputStream(path: ObjectPath): InputStream = throw failure
        }
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val observed = assertFailsWith<IOException> { tracked(source, target, cutoff = null) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        assertEquals(first, checkpoint())
    }

    @Test
    fun `tracked capture rejects caller transactions before storage I O`(): Unit = runBlocking {
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val target = mockk<ManagedObjectStorageService>()
        assertFailsWith<IllegalStateException> { withConnectionManager { transaction { tracked(source, target) } } }
        assertEquals(null, checkpoint())
        coVerify(exactly = 0) { target.getObjectInfo(any()) }
    }

    @Test
    fun `new capture commit failure leaves no false checkpoint and retry can use a fresh path`(): Unit = runBlocking {
        val progress = provide<ObjectBackupProgressService>()
        val failure = IOException("checkpoint commit failed")
        capture = ObjectBackupCaptureServiceImpl(object : ObjectBackupProgressService by progress {
            override suspend fun completeVerified(key: String, expectedVersion: Long, info: ObjectStorageInfo?): ObjectBackupProgress = throw failure
        }, Json, provide(), mockk())
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            val observed = assertFailsWith<IOException> { tracked(source) }
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            assertEquals(null, checkpoint())
            assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
            capture = provide()
            val result = tracked(source, path = "payloads/fresh-attempt")
            assertEquals("payloads/fresh-attempt", result.payload.payloadPath)
            assertEquals(ObjectBackupProgressStatus.COMPLETED, result.status)
        }
    }

    @Test
    fun `repair failure is preserved when its failure bookkeeping also fails`(): Unit = runBlocking {
        withConnectionManager { prepareKnown() }
        val progress = provide<ObjectBackupProgressService>()
        val failure = IOException("completion acknowledgement failed")
        val recording = IOException("failure recording failed")
        capture = ObjectBackupCaptureServiceImpl(object : ObjectBackupProgressService by progress {
            override suspend fun completeVerified(key: String, expectedVersion: Long, info: ObjectStorageInfo?): ObjectBackupProgress = throw failure
            override suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupProgress = throw recording
        }, Json, provide(), mockk())
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { source ->
            val observed = assertFailsWith<IOException> { tracked(source) }
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.flatMap { it.suppressed.asSequence() }.any {
                generateSequence(it) { it.cause }.any { it === recording }
            })
        }
        assertEquals(ObjectBackupProgressStatus.RUNNING, checkpoint()?.status)
        assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
    }

    @Test
    fun `a changed provider revision gets its own captured checkpoint and preserves the old payload`(): Unit = runBlocking {
        val first = provide<ObjectStorageServiceFactory>().withStorage(configuration()) { tracked(it) }
        val replacement = "replacement content".toByteArray()
        val changed = selected.copy(info = selected.info.copy(length = replacement.size.toLong(), version = "v2"))
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        coEvery { source.getObjectInfo(any()) } returns changed.info
        coEvery { source.getInputStream(any()) } answers { replacement.inputStream() }
        val next = tracked(source, path = "payloads/v2", entry = changed)
        assertTrue(first.key != next.key)
        assertEquals("v2", next.payload.revision)
        assertEquals(first, checkpoint())
        assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
        assertEquals("replacement content", retained.getString(StringObjectPath("payloads/v2")))
    }

    @Test
    fun `known revision repair rejects changed source bytes without changing its recorded digest`(): Unit = runBlocking {
        val prepared = withConnectionManager { prepareKnown() }
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        coEvery { source.getInputStream(any()) } answers { "x".repeat(content.length).byteInputStream() }
        assertFailsWith<BackupPayloadMismatchException> { tracked(source) }
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoint()?.status)
        assertEquals(prepared.payload, checkpoint()?.payload)
        provide<ObjectStorageServiceFactory>().withStorage(configuration()) { realSource ->
            val repaired = tracked(realSource)
            assertEquals(prepared.payload, repaired.payload)
            assertEquals(content, retained.getString(StringObjectPath(payloadPath)))
        }
    }

    @Test
    fun `tracked capture validates identities before source or destination access`(): Unit = runBlocking {
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val target = mockk<ManagedObjectStorageService>()
        val identity = listOf("old-instance", "retained-store", "content")
        for (i in identity.indices) {
            val changed = identity.toMutableList().apply { this[i] = " " }
            assertFailsWith<IllegalArgumentException> {
                capture.captureTracked(changed[0], changed[1], changed[2], "content", selected, source, target, payloadPath, null)
            }
        }
        assertEquals(null, checkpoint())
        coVerify(exactly = 0) { target.getObjectInfo(any()) }
    }

    @Test
    fun `tracked capture releases database connections during creation reuse and repair`(): Unit = runBlocking {
        val target = object : ManagedObjectStorageService by retained {
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
            val first = tracked(source, target)
            assertEquals(first, tracked(source, target))
            retained.delete(StringObjectPath(payloadPath))
            assertEquals(2, tracked(source, target).attempts)
        }
    }

    @Test
    fun `filesystem capture reuses its recorded checksum check when metadata-only reuse is requested`(): Unit = runBlocking {
        val first = provide<ObjectStorageServiceFactory>().withStorage(configuration()) { tracked(it) }
        endpoint.stop(0)
        var reads = 0
        val target = object : ManagedObjectStorageService by retained {
            override suspend fun getInputStream(path: ObjectPath): InputStream {
                reads++
                return retained.getInputStream(path)
            }
        }
        val source = mockk<ManagedObjectStorageService>()
        coEvery { source.getObjectInfo(any()) } returns selected.info
        val reused = capture.captureTracked(
            "old-instance", "retained-store", "content", "content", selected, source, target, payloadPath, OffsetDateTime.MIN,
        )
        assertEquals(first, reused)
        assertEquals(0, reads)
    }

    private suspend fun tracked(
        source: ManagedObjectStorageService,
        target: ManagedObjectStorageService = retained,
        path: String = payloadPath,
        entry: ObjectStorageEntry = selected,
        cutoff: OffsetDateTime? = OffsetDateTime.MIN,
    ) = capture.captureTracked("old-instance", "retained-store", "content", "content", entry, source, target, path, cutoff)

    private fun knownPayload() = BackupObject(
        "content", "asset", version, selected.info.length,
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).toHexString(), payloadPath,
    )

    private suspend fun prepareKnown() = provide<ObjectBackupProgressService>().prepare(
        ObjectBackupOperation.BACKUP, "old-instance", "retained-store", "content", knownPayload(),
    )

    private suspend fun checkpoint() = withConnectionManager {
        provide<ObjectBackupProgressService>().get(
            ObjectBackupOperation.BACKUP, "old-instance", "retained-store", "content", knownPayload(),
        )
    }

    private suspend fun writeRetained(path: String, value: String) {
        val bytes = value.toByteArray()
        retained.setInputStream(StringObjectPath(path), bytes.inputStream(), bytes.size.toLong())
    }

    private fun configuration() = StorageConfiguration(
        "s3", "", bucket = "bucket", forcePathStyle = true, region = "us-east-1",
        endpoint = "http://127.0.0.1:${endpoint.address.port}",
        accessKeyId = "CAPTURE_KEY", accessKeySecret = "test-secret",
    )

    private fun filesystem(name: String) =
        ManagedFileSystemObjectStorageService("", "", mockk(), directory.resolve(name).toString(), mockk())
}
