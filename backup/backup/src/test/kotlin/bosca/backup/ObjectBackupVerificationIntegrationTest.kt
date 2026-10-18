package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockk
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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

/** Real PostgreSQL/generated services and signed SDK metadata/version reads of retained bytes. */
@OptIn(InternalDI::class)
class ObjectBackupVerificationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var server: HttpServer
    private lateinit var reader: ObjectStorageService
    private lateinit var target: ObjectStorageService
    private lateinit var source: ObjectStorageService
    private lateinit var service: ObjectBackupCaptureService
    private val current = ConcurrentHashMap<String, String>()
    private val versions = ConcurrentHashMap<Pair<String, String>, Path>()
    private val sequence = AtomicInteger()
    private val sourceReads = AtomicInteger()
    private val heads = AtomicInteger()
    private val bodies = AtomicInteger()
    private val writes = AtomicInteger()
    private val versionReads = CopyOnWriteArrayList<String>()
    private val signatures = CopyOnWriteArrayList<String>()
    private val exposeVersion = AtomicBoolean(true)
    private val headFailure = AtomicInteger()
    private val bodyFailure = AtomicInteger()
    private val content = "verified retained bytes".toByteArray()
    private val selected = ObjectStorageEntry("asset", ObjectStorageInfo(content.size.toLong(), version = "source-v1"))
    private val payloadPath = "payloads/captured"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("object_backup_verification").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "object-backup-verification-test",
        ))
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        directory = createTempDirectory("bosca-retained-versions-")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                assertEquals(0, pool.activeConnections)
                signatures.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                val key = exchange.requestURI.path.removePrefix("/bucket/")
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                }
                val requested = query["versionId"]
                val version = requested ?: current[key]
                val file = version?.let { versions[key to it] }
                when (exchange.requestMethod) {
                    "HEAD" -> {
                        heads.incrementAndGet()
                        val status = headFailure.get().takeIf { it != 0 } ?: if (file == null) 404 else 200
                        if (status == 200) {
                            exchange.responseHeaders.add("Content-Length", Files.size(checkNotNull(file)).toString())
                            exchange.responseHeaders.add("ETag", "\"opaque-tag\"")
                            exchange.responseHeaders.add("Last-Modified", "Sat, 17 Oct 2026 00:00:00 GMT")
                            if (exposeVersion.get()) exchange.responseHeaders.add("x-amz-version-id", version)
                        }
                        exchange.sendResponseHeaders(status, -1)
                    }
                    "GET" -> {
                        bodies.incrementAndGet()
                        if (requested != null) versionReads.add(requested)
                        val status = bodyFailure.get().takeIf { it != 0 } ?: if (file == null) 404 else 200
                        val errorCode = if (status == 404) {
                            if (requested == null) "NoSuchKey" else "NoSuchVersion"
                        } else {
                            "AccessDenied"
                        }
                        val bytes = if (status == 200) Files.readAllBytes(checkNotNull(file)) else
                            "<Error><Code>$errorCode</Code><Message>Unavailable</Message></Error>".toByteArray()
                        if (status == 200 && exposeVersion.get()) exchange.responseHeaders.add("x-amz-version-id", version)
                        exchange.sendResponseHeaders(status, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                    "DELETE" -> {
                        current.remove(key)
                        exchange.sendResponseHeaders(204, -1)
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
            }
        }
        server.start()
        reader = provide<ObjectStorageServiceFactory>().create(StorageConfiguration(
            "s3", "", bucket = "bucket", forcePathStyle = true, region = "us-east-1",
            endpoint = "http://127.0.0.1:${server.address.port}", accessKeyId = "VERIFY_KEY", accessKeySecret = "test-secret",
        ))
        target = object : ObjectStorageService by reader {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                assertEquals(0, pool.activeConnections)
                writes.incrementAndGet()
                val version = "retained-${sequence.incrementAndGet()}"
                val file = directory.resolve(version)
                val written = Files.newOutputStream(file).use { stream.copyTo(it) }
                versions[path.toString() to version] = file
                current[path.toString()] = version
                return written
            }
        }
        source = mockk()
        coEvery { source.getInputStream(any(), "source-v1") } answers {
            assertEquals(0, pool.activeConnections)
            sourceReads.incrementAndGet()
            content.inputStream()
        }
        service = provide()
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::reader.isInitialized) reader.close()
            if (::server.isInitialized) server.stop(0)
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::directory.isInitialized) directory.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `new capture records the exact retained version whose checksum was read`(): Unit = runBlocking {
        val result = capture()
        assertEquals(current[payloadPath], result.verifiedVersion)
        assertNotNull(result.verified)
        assertEquals(listOf(result.verifiedVersion), versionReads.toList())
        assertEquals(1, bodies.get())
        assertEquals(1, sourceReads.get())
        assertTrue(signatures.all { it.contains("Credential=VERIFY_KEY/") })
    }

    @Test
    fun `unchanged fresh verification reuses one signed metadata request and no bodies or writes`(): Unit = runBlocking {
        val first = verifiedCapture()
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(first, result)
        assertEquals(1, heads.get())
        assertEquals(0, bodies.get())
        assertEquals(0, sourceReads.get())
        assertEquals(0, writes.get())
    }

    @Test
    fun `metadata-only reuse survives service restart without refreshing verification age`(): Unit = runBlocking {
        val first = verifiedCapture()
        BackupProviderRegistrar().register()
        service = provide()
        resetCounters()
        repeat(2) { assertEquals(first, capture(checkNotNull(first.verified).minusSeconds(1))) }
        assertEquals(2, heads.get())
        assertEquals(0, bodies.get())
        assertEquals(first, checkpoint(first.key))
    }

    @Test
    fun `an expired cutoff forces a version-addressed checksum check and refreshes evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        resetCounters()
        val refreshed = capture(checkNotNull(first.verified).plusSeconds(1))
        assertEquals(first.version + 1, refreshed.version)
        assertEquals(first.attempts, refreshed.attempts)
        assertEquals(first.payload, refreshed.payload)
        assertEquals(listOf(first.verifiedVersion), versionReads.toList())
        assertEquals(1, bodies.get())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `null policy always checks retained bytes despite fresh evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        resetCounters()
        val result = capture(null)
        assertEquals(first.version + 1, result.version)
        assertEquals(1, bodies.get())
        assertEquals(listOf(first.verifiedVersion), versionReads.toList())
    }

    @Test
    fun `a different retained version with matching bytes is verified before replacing evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        target.setInputStream(StringObjectPath(payloadPath), content.inputStream(), content.size.toLong())
        val replacement = current[payloadPath]
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(replacement, result.verifiedVersion)
        assertEquals(first.version + 1, result.version)
        assertEquals(first.attempts, result.attempts)
        assertEquals(listOf(replacement), versionReads.toList())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `a changed corrupt version is repaired instead of becoming verification evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        val wrong = ByteArray(content.size) { 'x'.code.toByte() }
        target.setInputStream(StringObjectPath(payloadPath), wrong.inputStream(), wrong.size.toLong())
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(ObjectBackupProgressStatus.COMPLETED, result.status)
        assertEquals(2, result.attempts)
        assertEquals(first.payload, result.payload)
        assertEquals(current[payloadPath], result.verifiedVersion)
        assertEquals(1, sourceReads.get())
        assertEquals(content.decodeToString(), Files.readString(checkNotNull(versions[payloadPath to checkNotNull(current[payloadPath])])))
    }

    @Test
    fun `forced periodic verification detects corruption under an unchanged provider version`(): Unit = runBlocking {
        val first = verifiedCapture()
        Files.write(checkNotNull(versions[payloadPath to checkNotNull(first.verifiedVersion)]), ByteArray(content.size) { 1 })
        resetCounters()
        assertEquals(first, capture(checkNotNull(first.verified).minusSeconds(1)))
        assertEquals(0, bodies.get())
        val repaired = capture(checkNotNull(first.verified).plusSeconds(1))
        assertEquals(2, repaired.attempts)
        assertEquals(first.payload, repaired.payload)
        assertEquals(1, sourceReads.get())
    }

    @Test
    fun `missing retained output cannot be reused by metadata policy`(): Unit = runBlocking {
        val first = verifiedCapture()
        current.remove(payloadPath)
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(2, result.attempts)
        assertEquals(current[payloadPath], result.verifiedVersion)
        assertEquals(1, sourceReads.get())
    }

    @Test
    fun `unversioned metadata does not reuse etags timestamps or lengths as verification proof`(): Unit = runBlocking {
        val first = verifiedCapture()
        exposeVersion.set(false)
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertNull(result.verifiedVersion)
        assertNull(result.verified)
        assertEquals(1, bodies.get())
        assertTrue(versionReads.isEmpty())
        resetCounters()
        capture(OffsetDateTime.MIN)
        assertEquals(1, bodies.get())
    }

    @Test
    fun `historical checkpoints without proof receive full verification before metadata-only reuse`(): Unit = runBlocking {
        val old = capture()
        clearProof(old.key)
        resetCounters()
        val verified = capture(OffsetDateTime.MIN)
        assertEquals(current[payloadPath], verified.verifiedVersion)
        assertNotNull(verified.verified)
        assertEquals(1, bodies.get())
        resetCounters()
        assertEquals(verified, capture(OffsetDateTime.MIN))
        assertEquals(0, bodies.get())
    }

    @Test
    fun `metadata failures cannot invalidate a completed checkpoint or fall back to body reads`(): Unit = runBlocking {
        val first = verifiedCapture()
        headFailure.set(403)
        resetCounters()
        assertFailsWith<Exception> { capture(checkNotNull(first.verified).minusSeconds(1)) }
        assertEquals(first, checkpoint(first.key))
        assertEquals(1, heads.get())
        assertEquals(0, bodies.get())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `full verification read failures preserve previous completed evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        bodyFailure.set(403)
        resetCounters()
        assertFailsWith<Exception> { capture(checkNotNull(first.verified).plusSeconds(1)) }
        assertEquals(first, checkpoint(first.key))
        assertEquals(1, bodies.get())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `changed length cannot be reused even when the provider version is unchanged`(): Unit = runBlocking {
        val first = verifiedCapture()
        Files.write(checkNotNull(versions[payloadPath to checkNotNull(first.verifiedVersion)]), byteArrayOf(1))
        resetCounters()
        val repaired = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(2, repaired.attempts)
        assertEquals(first.payload, repaired.payload)
        assertEquals(1, sourceReads.get())
    }

    @Test
    fun `cancellation during metadata or pinned reads cannot extend verification evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        val original = target
        for (duringMetadata in listOf(true, false)) {
            target = object : ObjectStorageService by original {
                override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                    if (duringMetadata) currentCoroutineContext().cancel(CancellationException("metadata cancelled"))
                    return original.getObjectInfo(path)
                }
                override suspend fun getInputStream(path: ObjectPath, version: String): InputStream {
                    currentCoroutineContext().cancel(CancellationException("verification cancelled"))
                    return original.getInputStream(path, version)
                }
            }
            val work = async { capture(checkNotNull(first.verified).plusSeconds(1)) }
            assertFailsWith<CancellationException> { work.await() }
            work.join()
            assertEquals(first, checkpoint(first.key))
        }
    }

    @Test
    fun `failed proof refresh preserves the previously verified completed checkpoint`(): Unit = runBlocking {
        val first = verifiedCapture()
        val failure = IOException("verification record failed")
        val progress = provide<ObjectBackupProgressService>()
        service = ObjectBackupCaptureServiceImpl(object : ObjectBackupProgressService by progress {
            override suspend fun recordVerification(key: String, expectedVersion: Long, objectVersion: String?): ObjectBackupProgress =
                throw failure
        })
        resetCounters()
        val observed = assertFailsWith<IOException> { capture(checkNotNull(first.verified).plusSeconds(1)) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        assertEquals(first, checkpoint(first.key))
        assertEquals(1, bodies.get())
        assertEquals(0, sourceReads.get())
    }

    private suspend fun capture(cutoff: OffsetDateTime? = null) = service.captureTracked(
        "source-instance", "retained-store", "content", "content", selected, source, target, payloadPath, cutoff,
    )

    private suspend fun verifiedCapture(): ObjectBackupProgress {
        val captured = capture()
        assertNotNull(captured.verifiedVersion)
        assertNotNull(captured.verified)
        return captured
    }

    private fun clearProof(key: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update backup.object_progress set verified_version = null, verified = null where key = ?").use {
                it.setString(1, key)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private suspend fun checkpoint(key: String) = withConnectionManager {
        checkNotNull(provide<ObjectBackupProgressService>().get(key))
    }

    private fun resetCounters() {
        sourceReads.set(0)
        heads.set(0)
        bodies.set(0)
        writes.set(0)
        versionReads.clear()
    }
}
