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
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hard process termination and durable checkpoints; queue action wiring is exercised separately. */
@OptIn(InternalDI::class)
class ObjectBackupJobCheckpointIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool
    private lateinit var root: Path
    private lateinit var application: BoscaApplication
    private lateinit var configurations: Map<String, StorageConfiguration>
    private var worker: Process? = null

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        postgres = SharedPostgreSQLContainer().withDatabaseName("job_checkpoint").apply { start() }
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4,
        ), key = "job-checkpoint-test"))
        root = createTempDirectory("bosca-job-checkpoint-").toRealPath()
        Files.createDirectories(root.resolve("source"))
        Files.writeString(root.resolve("source/a"), "data")
        Files.writeString(root.resolve("source/b"), "more")
        configurations = listOf("source", "retained").associateWith {
            StorageConfiguration("fs", "", basePath = root.resolve(it).toString())
        }
        val config = buildJsonObject {
            put("backup", buildJsonObject {
                put("objectStores", JsonObject(configurations.mapValues { (_, value) ->
                    Json.encodeToJsonElement(StorageConfiguration.serializer(), value)
                }))
            })
        }
        Files.writeString(root.resolve("application.json"), Json.encodeToString(JsonObject.serializer(), config))
        application = BoscaApplication(
            ApplicationConfig.loadFromFile(root.resolve("application.json").toString()), ownsServices = false,
        )
        install()
        FlywayMigration(pool).migrate(listOf(provide<Migration>(name = "backup-migrations")))
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            worker?.let { if (it.isAlive) { it.destroyForcibly(); check(it.waitFor(10, TimeUnit.SECONDS)) } }
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            if (::postgres.isInitialized) postgres.stop()
            if (::root.isInitialized) root.toFile().deleteRecursively()
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `hard-stopped capture resumes its job checkpoint and only copies unfinished source objects`(): Unit = runBlocking {
        val service = provide<ObjectBackupCaptureService>()
        val plan = service.discoverConfigured(ObjectBackupDiscoveryRequest(
            "selected", "source-instance", "content", "retained",
            mapOf("content" to ObjectBackupSource("source", "")), "payloads", "manifests/selected.json",
        ))
        val checkpoint = UUID.randomUUID().toString()
        val settingsPath = root.resolve("worker.properties")
        Properties().apply {
            setProperty("root", root.toString())
            setProperty("jdbcUrl", postgres.jdbcUrl)
            setProperty("username", postgres.username)
            setProperty("password", postgres.password)
            setProperty("planKey", plan.key)
            setProperty("checkpointKey", checkpoint)
        }.also { settings -> Files.newOutputStream(settingsPath).use { settings.store(it, null) } }
        val classpath = checkNotNull(System.getProperty("bosca.backup.testClasspath"))
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath,
            ObjectBackupCheckpointWorker::class.java.name, settingsPath.toString(),
        ).redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start()
        worker = process
        withTimeout(30_000) {
            while (!root.resolve("worker-ready").exists()) {
                check(process.isAlive) { "Capture worker exited before its checkpoint: ${Files.readString(root.resolve("worker.log"))}" }
                delay(25)
            }
        }
        val owner = withConnectionManager { assertNotNull(service.getWriter(plan.key)) }
        val before = withConnectionManager {
            provide<ObjectBackupProgressService>().list(
                ObjectBackupOperation.BACKUP, "source-instance", "retained", "content", 0, 10,
            )
        }.single()
        assertEquals("a", before.payload.key)
        assertEquals(ObjectBackupProgressStatus.COMPLETED, before.status)
        assertTrue(owner.key.startsWith("$checkpoint:"))
        assertTrue(!root.resolve("retained/${plan.path}").exists())
        process.destroyForcibly()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertNotEquals(0, process.exitValue())
        assertEquals(owner, withConnectionManager { service.getWriter(plan.key) })

        // Recreate the services and observe real filesystem I/O during the redelivered capture.
        install()
        val factory = provide<ObjectStorageServiceFactory>()
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by factory {
                override suspend fun <T> withStorage(
                    configuration: StorageConfiguration,
                    block: suspend (ObjectStorageService) -> T,
                ): T = factory.withStorage(configuration) { actual ->
                    block(object : ObjectStorageService by actual {
                        override suspend fun getInputStream(path: ObjectPath): InputStream {
                            if (configuration.basePath == root.resolve("source").toString()) reads.add(path.toString())
                            return actual.getInputStream(path)
                        }

                        override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                            writes.add(path.toString())
                            return actual.setInputStream(path, stream, length)
                        }
                    })
                }
            }
        }
        val resumed = provide<ObjectBackupCaptureService>().captureConfigured(plan.key, OffsetDateTime.MIN, checkpoint)
        assertEquals(listOf("b"), reads)
        assertEquals(1, writes.count { it.startsWith("payloads/") })
        assertEquals(before.payload, resumed.objects.single { it.key == "a" })
        assertEquals(listOf("a", "b"), resumed.objects.map { it.key })
        assertNull(withConnectionManager { provide<ObjectBackupCaptureService>().getWriter(plan.key) })
        factory.withStorage(configurations.getValue("retained")) { retained ->
            val published = ObjectBackupManifestStore(retained, application.json).load(StringObjectPath(plan.path))
            assertEquals(resumed, published)
            ObjectBackupManifestStore(retained, application.json).verify(published)
        }
    }

    private fun install() {
        ProviderRegistry.clear()
        provides<BoscaApplication>(singleton = true) { application }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { application.json }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
    }
}
