package bosca.backup

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Properties
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Separate JVM stopped forcibly after one completed object, without executing capture cleanup. */
@OptIn(InternalDI::class)
object ObjectBackupCheckpointWorker {
    @JvmStatic
    fun main(arguments: Array<String>): Unit = runBlocking {
        val settings = Properties().apply { Files.newInputStream(Path.of(arguments.single())).use { load(it) } }
        val root = Path.of(settings.getProperty("root"))
        val application = BoscaApplication(
            ApplicationConfig.loadFromFile(root.resolve("application.json").toString()), ownsServices = false,
        )
        val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            settings.getProperty("jdbcUrl"), settings.getProperty("username"), settings.getProperty("password"),
            maxConnections = 2,
        ), key = "checkpoint-worker"))
        ProviderRegistry.clear()
        provides<BoscaApplication>(singleton = true) { application }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { application.json }
        StorageProviderRegistrar().register()
        val factory = provide<ObjectStorageServiceFactory>()
        val target = settings.getProperty("targetStorage", "retained")
        var payloads = 0
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by factory {
                override suspend fun <T> withStorage(
                    configuration: StorageConfiguration,
                    block: suspend (ObjectStorageService) -> T,
                ): T = factory.withStorage(configuration) { actual ->
                    val store = if (configuration.basePath != root.resolve(target).toString()) actual else
                        object : ObjectStorageService by actual {
                            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                                if (!path.toString().endsWith(".json") && ++payloads == 2) {
                                    Files.writeString(root.resolve("worker-ready"), "second payload")
                                    awaitCancellation()
                                }
                                return actual.setInputStream(path, stream, length)
                            }
                        }
                    block(store)
                }
            }
        }
        BackupProviderRegistrar().register()
        try {
            val runKey = settings.getProperty("runKey")
            if (runKey == null) {
                provide<ObjectBackupCaptureService>().captureConfigured(
                    settings.getProperty("planKey"), OffsetDateTime.MIN, settings.getProperty("checkpointKey"),
                )
            } else {
                val runs = provide<ObjectBackupRunService>()
                val version = settings.getProperty("version")?.toLong()
                if (version == null) runs.executeConfigured(runKey, settings.getProperty("checkpointKey"))
                else runs.executeConfigured(runKey, version, settings.getProperty("checkpointKey"))
            }
            error("Worker unexpectedly completed before interruption")
        } finally {
            application.shutdown()
            pool.close()
        }
    }
}
