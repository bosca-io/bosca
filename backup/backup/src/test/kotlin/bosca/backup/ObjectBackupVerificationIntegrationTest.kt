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

/** Real PostgreSQL/generated services and signed SDK metadata and current body reads of retained bytes. */
@OptIn(InternalDI::class)
class ObjectBackupVerificationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var directory: Path
    private lateinit var server: HttpServer
    private lateinit var reader: ManagedObjectStorageService
    private lateinit var target: ManagedObjectStorageService
    private lateinit var source: ManagedObjectStorageService
    private lateinit var service: ObjectBackupCaptureService
    private val current = ConcurrentHashMap<String, String>()
    private val versions = ConcurrentHashMap<Pair<String, String>, Path>()
    private val sequence = AtomicInteger()
    private val sourceReads = AtomicInteger()
    private val heads = AtomicInteger()
    private val bodies = AtomicInteger()
    private val manifestReads = AtomicInteger()
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
        provides<Json>(singleton = true) { backupTestJson }
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
                        if (key.startsWith("manifests/")) manifestReads.incrementAndGet()
                        else bodies.incrementAndGet()
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
        target = object : ManagedObjectStorageService by reader {
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
        coEvery { source.getObjectInfo(any()) } returns selected.info
        coEvery { source.getInputStream(any()) } answers {
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
        assertEquals(current[payloadPath], result.verifiedInfo?.version)
        assertNotNull(result.verified)
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
    fun `an expired cutoff forces a checksum check and refreshes evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        resetCounters()
        val refreshed = capture(checkNotNull(first.verified).plusSeconds(1))
        assertEquals(first.version + 1, refreshed.version)
        assertEquals(first.attempts, refreshed.attempts)
        assertEquals(first.payload, refreshed.payload)
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
    }

    @Test
    fun `a different retained version with matching bytes is verified before replacing evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        target.setInputStream(StringObjectPath(payloadPath), content.inputStream(), content.size.toLong())
        val replacement = current[payloadPath]
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(replacement, result.verifiedInfo?.version)
        assertEquals(first.version + 1, result.version)
        assertEquals(first.attempts, result.attempts)
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
        assertEquals(current[payloadPath], result.verifiedInfo?.version)
        assertEquals(1, sourceReads.get())
        assertEquals(content.decodeToString(), Files.readString(checkNotNull(versions[payloadPath to checkNotNull(current[payloadPath])])))
    }

    @Test
    fun `forced periodic verification detects corruption under an unchanged provider version`(): Unit = runBlocking {
        val first = verifiedCapture()
        Files.write(checkNotNull(versions[payloadPath to checkNotNull(first.verifiedInfo?.version)]), ByteArray(content.size) { 1 })
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
        assertEquals(current[payloadPath], result.verifiedInfo?.version)
        assertEquals(1, sourceReads.get())
    }

    @Test
    fun `unversioned observations reuse a real checksum check without treating metadata as a digest`(): Unit = runBlocking {
        val first = verifiedCapture()
        exposeVersion.set(false)
        resetCounters()
        val result = capture(checkNotNull(first.verified).minusSeconds(1))
        assertNull(result.verifiedInfo?.version)
        assertNotNull(result.verified)
        assertEquals(1, bodies.get())
        assertTrue(versionReads.isEmpty())
        resetCounters()
        assertEquals(result, capture(OffsetDateTime.MIN))
        assertEquals(0, bodies.get())
    }

    @Test
    fun `historical checkpoints without proof receive full verification before metadata-only reuse`(): Unit = runBlocking {
        val old = capture()
        clearProof(old.key)
        resetCounters()
        val verified = capture(OffsetDateTime.MIN)
        assertEquals(current[payloadPath], verified.verifiedInfo?.version)
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
        Files.write(checkNotNull(versions[payloadPath to checkNotNull(first.verifiedInfo?.version)]), byteArrayOf(1))
        resetCounters()
        val repaired = capture(checkNotNull(first.verified).minusSeconds(1))
        assertEquals(2, repaired.attempts)
        assertEquals(first.payload, repaired.payload)
        assertEquals(1, sourceReads.get())
    }

    @Test
    fun `cancellation during metadata or body reads cannot extend verification evidence`(): Unit = runBlocking {
        val first = verifiedCapture()
        val original = target
        for (duringMetadata in listOf(true, false)) {
            target = object : ManagedObjectStorageService by original {
                override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                    if (duringMetadata) currentCoroutineContext().cancel(CancellationException("metadata cancelled"))
                    return original.getObjectInfo(path)
                }
                override suspend fun getInputStream(path: ObjectPath): InputStream {
                    currentCoroutineContext().cancel(CancellationException("verification cancelled"))
                    return original.getInputStream(path)
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
            override suspend fun recordVerification(key: String, expectedVersion: Long, info: ObjectStorageInfo?): ObjectBackupProgress =
                throw failure
        }, Json, provide(), mockk())
        resetCounters()
        val observed = assertFailsWith<IOException> { capture(checkNotNull(first.verified).plusSeconds(1)) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        assertEquals(first, checkpoint(first.key))
        assertEquals(1, bodies.get())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `tracked manifest publication reuses captured proof without reading asset bodies`(): Unit = runBlocking {
        val captured = verifiedCapture()
        resetCounters()
        publish(captured)
        assertEquals(1, manifestReads.get())
        assertEquals(1, heads.get())
        assertEquals(0, bodies.get())
        assertEquals(0, sourceReads.get())
        assertEquals(1, writes.get())
        assertEquals(captured, checkpoint(captured.key))
        assertEquals(manifest(captured), ObjectBackupManifestStore(target, Json).load(manifestPath))
    }

    @Test
    fun `strict and expired publication policies fully check the exact retained version`(): Unit = runBlocking {
        var captured = verifiedCapture()
        for (cutoff in listOf(null, checkNotNull(captured.verified).plusSeconds(1))) {
            resetCounters()
            publish(captured, cutoff)
            assertEquals(1, bodies.get())
            assertEquals(0, sourceReads.get())
            val refreshed = checkpoint(captured.key)
            assertEquals(captured.version + 1, refreshed.version)
            assertEquals(captured.attempts, refreshed.attempts)
            captured = refreshed
        }
    }

    @Test
    fun `publication verifies a changed retained version before refreshing checkpoint proof`(): Unit = runBlocking {
        val captured = verifiedCapture()
        target.setInputStream(StringObjectPath(payloadPath), content.inputStream(), content.size.toLong())
        val replacement = current[payloadPath]
        resetCounters()
        publish(captured)
        assertEquals(1, bodies.get())
        assertEquals(replacement, checkpoint(captured.key).verifiedInfo?.version)
        assertEquals(captured.attempts, checkpoint(captured.key).attempts)
    }

    @Test
    fun `publication corruption invalidates completed proof and prevents manifest output`(): Unit = runBlocking {
        val captured = verifiedCapture()
        Files.write(checkNotNull(versions[payloadPath to checkNotNull(captured.verifiedInfo?.version)]), ByteArray(content.size) { 1 })
        resetCounters()
        assertFailsWith<BackupPayloadMismatchException> { publish(captured, null) }
        val failed = checkpoint(captured.key)
        assertEquals(ObjectBackupProgressStatus.FAILED, failed.status)
        assertNull(failed.verified)
        assertNull(failed.verifiedInfo?.version)
        assertEquals(0, writes.get())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `publication absence invalidates proof without falling back to the source`(): Unit = runBlocking {
        val captured = verifiedCapture()
        current.remove(payloadPath)
        resetCounters()
        assertFailsWith<ObjectNotFoundException> { publish(captured) }
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoint(captured.key).status)
        assertEquals(0, writes.get())
        assertEquals(0, sourceReads.get())
    }

    @Test
    fun `publication metadata and read outages preserve proof and prevent manifest output`(): Unit = runBlocking {
        val captured = verifiedCapture()
        for (metadata in listOf(true, false)) {
            headFailure.set(if (metadata) 403 else 0)
            bodyFailure.set(if (metadata) 0 else 403)
            resetCounters()
            assertFailsWith<Exception> { publish(captured, null) }
            assertEquals(captured, checkpoint(captured.key))
            assertEquals(0, writes.get())
        }
    }

    @Test
    fun `publication cancellation after metadata prevents output and leaves proof unchanged`(): Unit = runBlocking {
        val captured = verifiedCapture()
        val original = target
        target = object : ManagedObjectStorageService by original {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                currentCoroutineContext().cancel(CancellationException("publication cancelled"))
                return original.getObjectInfo(path)
            }
        }
        resetCounters()
        val work = async { publish(captured) }
        assertFailsWith<CancellationException> { work.await() }
        work.join()
        assertEquals(captured, checkpoint(captured.key))
        assertEquals(0, writes.get())
    }

    @Test
    fun `publication proof refresh failure prevents manifest output and preserves original error`(): Unit = runBlocking {
        val captured = verifiedCapture()
        val failure = IOException("publication proof failed")
        val original = provide<ObjectBackupProgressService>()
        val progress = object : ObjectBackupProgressService by original {
            override suspend fun recordVerification(
                key: String,
                expectedVersion: Long,
                info: ObjectStorageInfo?,
            ): ObjectBackupProgress = throw failure
        }
        resetCounters()
        val observed = assertFailsWith<IOException> { publish(captured, null, progress) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
        assertEquals(captured, checkpoint(captured.key))
        assertEquals(0, writes.get())
    }

    @Test
    fun `shared payload publication verifies once and refreshes every revision needing proof`(): Unit = runBlocking {
        val captured = verifiedCapture()
        val other = withConnectionManager {
            val progress = provide<ObjectBackupProgressService>()
            val pending = progress.prepare(
                ObjectBackupOperation.BACKUP, "source-instance", "retained-store", "content",
                captured.payload.copy(store = "artifacts", key = "other"),
            )
            val running = progress.start(pending.key, pending.version)
            progress.complete(running.key, running.version)
        }
        val selected = manifest(captured).copy(objects = listOf(captured.payload, other.payload))
        resetCounters()
        ObjectBackupManifestStore(target, Json).saveTracked(
            manifestPath, selected, "retained-store", provide(), checkNotNull(captured.verified).minusSeconds(1),
        )
        assertEquals(2, heads.get())
        assertEquals(1, bodies.get())
        assertEquals(captured.verifiedInfo?.version, checkpoint(other.key).verifiedInfo?.version)
        assertNotNull(checkpoint(other.key).verified)
        assertEquals(selected, ObjectBackupManifestStore(target, Json).load(manifestPath))
    }

    @Test
    fun `publication keeps valid proof when manifest write fails or reports incomplete output`(): Unit = runBlocking {
        val captured = verifiedCapture()
        val original = target
        for (incomplete in listOf(false, true)) {
            target = object : ManagedObjectStorageService by original {
                override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                    if (incomplete) return 0
                    throw IOException("manifest unavailable")
                }
            }
            assertFailsWith<Exception> { publish(captured) }
            assertEquals(captured, checkpoint(captured.key))
        }
    }

    @Test
    fun `publication cannot use a checkpoint invalidated during retained metadata I O`(): Unit = runBlocking {
        val captured = verifiedCapture()
        val original = target
        target = object : ManagedObjectStorageService by original {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                val info = original.getObjectInfo(path)
                withConnectionManager {
                    provide<ObjectBackupProgressService>().invalidate(captured.key, captured.version, "stopped")
                }
                return info
            }
        }
        resetCounters()
        assertFailsWith<IllegalStateException> { publish(captured) }
        assertEquals(0, writes.get())
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoint(captured.key).status)
    }

    @Test
    fun `publication preserves original absence when invalidation bookkeeping fails`(): Unit = runBlocking {
        val captured = verifiedCapture()
        current.remove(payloadPath)
        val failure = IOException("invalidation unavailable")
        val original = provide<ObjectBackupProgressService>()
        val progress = object : ObjectBackupProgressService by original {
            override suspend fun invalidate(key: String, expectedVersion: Long, error: String): ObjectBackupProgress =
                throw failure
        }
        resetCounters()
        val observed = assertFailsWith<ObjectNotFoundException> { publish(captured, null, progress) }
        assertTrue(generateSequence<Throwable>(observed) { it.cause }.flatMap { it.suppressed.asSequence() }.any {
            generateSequence(it) { it.cause }.any { cause -> cause === failure }
        })
        assertEquals(captured, checkpoint(captured.key))
        assertEquals(0, writes.get())
    }

    @Test
    fun `publication records unversioned observations once and reuses them without refreshing checksum age`(): Unit = runBlocking {
        val captured = verifiedCapture()
        exposeVersion.set(false)
        resetCounters()
        publish(captured)
        val checked = checkpoint(captured.key)
        assertEquals(1, bodies.get())
        assertTrue(versionReads.isEmpty())
        assertNull(checked.verifiedInfo?.version)
        assertNotNull(checked.verified)
        resetCounters()
        publish(captured)
        assertEquals(0, bodies.get())
        assertEquals(checked, checkpoint(captured.key))
    }

    @Test
    fun `selected inventory capture and publication avoid all asset body reads on an unchanged pass`(): Unit = runBlocking {
        val selectedInventory = ObjectBackupCaptureInventory(
            "inventory", "source-instance", "content", "2026-10-18T00:00:00Z", mapOf("content" to listOf(selected)),
        )
        val path = "manifests/inventory.json"
        val first = service.captureInventory(
            selectedInventory, "retained-store", mapOf("content" to source), target, "payloads/inventory", path, OffsetDateTime.MIN,
        )
        assertEquals(1, first.objects.size)
        assertEquals(1, bodies.get())
        assertEquals(1, sourceReads.get())
        resetCounters()
        val repeated = service.captureInventory(
            selectedInventory, "retained-store", mapOf("content" to source), target, "another-prefix", path, OffsetDateTime.MIN,
        )
        assertEquals(first, repeated)
        assertEquals(2, heads.get())
        assertEquals(0, bodies.get())
        assertEquals(0, sourceReads.get())
        assertEquals(1, writes.get())
        assertEquals(first, ObjectBackupManifestStore(target, Json).load(StringObjectPath(path)))
    }

    @Test
    fun `saved plan uses the execution cutoff without freezing verification policy in durable input`(): Unit = runBlocking {
        val selectedInventory = ObjectBackupCaptureInventory(
            "planned", "source-instance", "content", "2026-10-18T00:00:00Z", mapOf("content" to listOf(selected)),
        )
        val plan = withConnectionManager {
            service.prepareInventory(selectedInventory, "retained-store", "payloads/planned", "manifests/planned.json")
        }
        val first = service.captureInventory(plan.key, mapOf("content" to source), target, OffsetDateTime.MIN)
        assertEquals(1, sourceReads.get())
        resetCounters()
        assertEquals(first, service.captureInventory(plan.key, mapOf("content" to source), target, OffsetDateTime.MIN))
        assertEquals(0, sourceReads.get())
        assertEquals(0, bodies.get())
        resetCounters()
        assertEquals(first, service.captureInventory(plan.key, mapOf("content" to source), target, null))
        assertEquals(0, sourceReads.get())
        assertEquals(2, bodies.get())
        assertEquals(plan, withConnectionManager { service.getPlan(plan.key) })
    }

    private val manifestPath = StringObjectPath("manifests/captured.json")

    private fun manifest(progress: ObjectBackupProgress) = ObjectBackupManifest(
        "captured", "source-instance", "content", "2026-10-17T00:00:00Z", listOf(progress.payload),
    )

    private suspend fun publish(
        captured: ObjectBackupProgress,
        cutoff: OffsetDateTime? = checkNotNull(captured.verified).minusSeconds(1),
        progress: ObjectBackupProgressService? = null,
    ) = ObjectBackupManifestStore(target, Json).saveTracked(
        manifestPath, manifest(captured), "retained-store", progress ?: provide(), cutoff,
    )

    private suspend fun capture(cutoff: OffsetDateTime? = null) = service.captureTracked(
        "source-instance", "retained-store", "content", "content", selected, source, target, payloadPath, cutoff,
    )

    private suspend fun verifiedCapture(): ObjectBackupProgress {
        val captured = capture()
        assertNotNull(captured.verifiedInfo?.version)
        assertNotNull(captured.verified)
        return captured
    }

    private fun clearProof(key: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update backup.object_progress set verified_info = null, verified = null where key = ?").use {
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
        manifestReads.set(0)
        writes.set(0)
        versionReads.clear()
    }
}
