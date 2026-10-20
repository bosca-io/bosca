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
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real generated services/PostgreSQL and unversioned SDK reads and multipart writes at both ends. */
@OptIn(InternalDI::class)
class ObjectBackupUnversionedDestinationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var endpoint: UnversionedObjectStorageEndpoint
    private lateinit var application: BoscaApplication
    private lateinit var service: ObjectBackupCaptureService
    private lateinit var configurations: Map<String, StorageConfiguration>
    private val firstKey = "metadata/a/1/content"
    private val secondKey = "metadata/b/1/content"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("unversioned_destination").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 3),
            key = "unversioned-destination",
        ))
        endpoint = UnversionedObjectStorageEndpoint()
        endpoint.put("source/$firstKey", "first".toByteArray())
        endpoint.put("source/$secondKey", "other".toByteArray())
        configurations = listOf("source", "retained").associateWith { bucket ->
            StorageConfiguration("s3", "", bucket = bucket, region = "us-east-1", forcePathStyle = true,
                endpoint = endpoint.endpoint, accessKeyId = "UNVERSIONED_KEY", accessKeySecret = "test-secret")
        }
        val root = buildJsonObject {
            put("backup", buildJsonObject { put("objectStores", JsonObject(configurations.mapValues {
                Json.encodeToJsonElement(StorageConfiguration.serializer(), it.value)
            })) })
        }
        application = BoscaApplication(ApplicationConfig.load(root.toString().byteInputStream()), ownsServices = false)
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<BoscaApplication>(singleton = true) { application }
        provides<Json>(singleton = true) { application.json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
        service = provide()
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::application.isInitialized) application.shutdown()
            if (::endpoint.isInitialized) endpoint.close()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `ordinary SDK uploads and reads do not expose provider versions`(): Unit = runBlocking {
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue("retained")) { storage ->
            val path = StringObjectPath("fixture")
            assertEquals(5, storage.setInputStream(path, "bytes".byteInputStream(), 5))
            assertNull(storage.getObjectInfo(path).version)
            assertEquals("bytes", storage.getString(path))
        }
        assertTrue(endpoint.signatures.isNotEmpty())
        assertTrue(endpoint.signatures.all { "Credential=UNVERSIONED_KEY/" in it })
    }

    @Test
    fun `unchanged unversioned copies read or write no asset bodies and preserve checksum age`(): Unit = runBlocking {
        val first = copy("first")
        val checked = checkpoints()
        assertTrue(checked.all { it.verified != null })
        assertEquals(2, endpoint.bodies.count { it.startsWith("source/") })
        assertEquals(2, endpoint.bodies.count { it.startsWith("retained/payloads/") })
        restart()
        endpoint.resetCounters()
        val same = copy("same")
        assertEquals(first.objects, same.objects)
        assertTrue(endpoint.bodies.isEmpty(), endpoint.bodies.toString())
        assertTrue(assetWrites().isEmpty())
        assertEquals(checked, checkpoints())
    }

    @Test
    fun `one changed same length object copies only that source and its retained payload`(): Unit = runBlocking {
        val first = copy("first")
        endpoint.put("source/$secondKey", "newer".toByteArray())
        endpoint.resetCounters()
        val changed = copy("changed")
        assertEquals(first.objects.first(), changed.objects.first())
        assertEquals(listOf("source/$secondKey"), endpoint.bodies.filter { it.startsWith("source/") })
        assertEquals(1, endpoint.bodies.count { it.startsWith("retained/payloads/") })
        assertEquals(1, assetWrites().size)
        assertContentEquals("other".toByteArray(), endpoint.bytes("retained/${first.objects.last().payloadPath}"))
        assertContentEquals("newer".toByteArray(), endpoint.bytes("retained/${changed.objects.last().payloadPath}"))
    }

    @Test
    fun `changed retained metadata triggers one checksum check before reuse`() = runBlocking {
        val first = copy("first")
        val before = checkpoints().single { it.payload.key == firstKey }
        val path = "retained/${first.objects.first().payloadPath}"
        endpoint.put(path, endpoint.bytes(path))
        endpoint.resetCounters()
        assertEquals(first.objects, copy("new-observation").objects)
        assertEquals(listOf(path), endpoint.bodies.toList())
        assertTrue(assetWrites().isEmpty())
        val checked = checkpoints().single { it.payload.key == firstKey }
        assertEquals(before.attempts, checked.attempts)
        assertEquals(before.version + 1, checked.version)
        endpoint.resetCounters()
        copy("reused-observation")
        assertTrue(endpoint.bodies.isEmpty())
        assertEquals(checked, checkpoints().single { it.payload.key == firstKey })
    }

    @Test
    fun `an expired checksum policy rereads retained bytes then preserves the refreshed age`(): Unit = runBlocking {
        copy("first")
        val before = checkpoints()
        val cutoff = before.maxOf { assertNotNull(it.verified) }.plusNanos(1)
        endpoint.resetCounters()
        copy("expired", cutoff)
        assertEquals(2, endpoint.bodies.size)
        assertTrue(endpoint.bodies.all { it.startsWith("retained/payloads/") })
        assertTrue(assetWrites().isEmpty())
        val checked = checkpoints()
        assertTrue(checked.all { assertNotNull(it.verified) >= cutoff })
        endpoint.resetCounters()
        copy("fresh")
        assertTrue(endpoint.bodies.isEmpty())
        assertEquals(checked, checkpoints())
    }

    @Test
    fun `unfinished unversioned copy retries after restart without rereading completed assets`(): Unit = runBlocking {
        val selected = service.discoverConfigured(request("partial"))
        endpoint.failRead = "source/$secondKey"
        assertFailsWith<Exception> { service.captureConfigured(selected.key, OffsetDateTime.MIN) }
        endpoint.failRead = null
        restart()
        endpoint.resetCounters()
        assertEquals(2, service.captureConfigured(selected.key, OffsetDateTime.MIN).objects.size)
        assertEquals(listOf("source/$secondKey"), endpoint.bodies.filter { it.startsWith("source/") })
        assertEquals(1, endpoint.bodies.count { it.startsWith("retained/payloads/") })
        assertEquals(1, assetWrites().size)
    }

    @Test
    fun `explicit integrity checking detects corruption even when reported metadata stays unchanged`(): Unit = runBlocking {
        val first = copy("first")
        val path = "retained/${first.objects.first().payloadPath}"
        endpoint.put(path, "wrong".toByteArray(), preserveMetadata = true)
        endpoint.resetCounters()
        assertEquals(first.objects, copy("metadata-only").objects)
        assertTrue(endpoint.bodies.isEmpty())
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue("retained")) { storage ->
            assertFailsWith<BackupPayloadMismatchException> { ObjectBackupManifestStore(storage, application.json).verify(first) }
        }
        assertTrue(endpoint.bodies.isNotEmpty())
        val repaired = copy("integrity", null)
        assertEquals(first.objects, repaired.objects)
        assertContentEquals("first".toByteArray(), endpoint.bytes(path))
        assertEquals(2, checkpoints().single { it.payload.key == firstKey }.attempts)
    }

    @Test
    fun `reported destination changes during full verification prevent manifest publication`(): Unit = runBlocking {
        val first = copy("first")
        val path = "retained/${first.objects.first().payloadPath}"
        endpoint.afterRead = { key ->
            if (key == path) {
                endpoint.afterRead = null
                endpoint.put(path, "wrong".toByteArray())
            }
        }
        provide<ObjectStorageServiceFactory>().withStorage(configurations.getValue("retained")) { storage ->
            assertFailsWith<BackupPayloadMismatchException> {
                ObjectBackupManifestStore(storage, application.json).saveTracked(
                    StringObjectPath("manifests/raced.json"), first.copy(id = "raced"), "retained", provide(), null,
                )
            }
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> {
                storage.getObjectInfo(StringObjectPath("manifests/raced.json"))
            }
        }
        assertEquals(ObjectBackupProgressStatus.FAILED, checkpoints().single { it.payload.key == firstKey }.status)
    }

    private fun request(id: String) = ObjectBackupDiscoveryRequest(id, "instance", "content", "retained",
        mapOf("content" to ObjectBackupSource("source", "metadata/")), "payloads", "manifests/$id.json")

    private suspend fun copy(id: String, cutoff: OffsetDateTime? = OffsetDateTime.MIN): ObjectBackupManifest =
        service.captureConfigured(service.discoverConfigured(request(id)).key, cutoff)

    private suspend fun checkpoints() = withConnectionManager {
        provide<ObjectBackupProgressService>().list(ObjectBackupOperation.BACKUP, "instance", "retained", "content", 0, 20)
    }

    private suspend fun restart() {
        BackupProviderRegistrar().register()
        service = provide()
    }

    private fun assetWrites() = endpoint.writes.filter { it.startsWith("retained/payloads/") }
}
