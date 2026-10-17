package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StorageDispatcher
import java.io.File
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Checks configured storage addresses before handing the selected configuration to the factory. */
@ServiceImplementation
class ObjectBackupStorageServiceImpl(
    private val repository: ObjectBackupStorageRepository,
    private val factory: ObjectStorageServiceFactory,
) : ObjectBackupStorageService {
    override suspend fun <T> withStorages(
        configurations: Map<String, StorageConfiguration>,
        block: suspend (Map<String, ObjectStorageService>) -> T,
    ): T {
        val captured = configurations.toMap()
        check(connectionOrNull()?.inTransaction != true) {
            "Backup storage must be opened outside an active database transaction"
        }
        require(captured.keys.all { it.isNotBlank() }) { "Backup storage identities are required" }
        val selected = linkedMapOf<String, StorageConfiguration>()
        for ((key, configuration) in captured.toSortedMap()) selected[key] = select(configuration)
        withConnectionManager {
            transaction {
                for ((key, configuration) in selected) bindSelected(key, configuration)
            }
        }
        return withContext(StorageDispatcher) {
            openStorages(selected.entries.toList(), 0, linkedMapOf(), block)
        }
    }

    private suspend fun <T> openStorages(
        entries: List<Map.Entry<String, StorageConfiguration>>,
        index: Int,
        opened: MutableMap<String, ObjectStorageService>,
        block: suspend (Map<String, ObjectStorageService>) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        if (index == entries.size) return block(opened.toMap())
        val (key, configuration) = entries[index]
        return factory.withStorage(configuration) { store ->
            opened[key] = store
            try {
                openStorages(entries, index + 1, opened, block)
            } finally {
                opened.remove(key)
            }
        }
    }

    override suspend fun get(key: String): ObjectBackupStorage? {
        require(key.isNotBlank()) { "Backup storage identity is required" }
        return repository.get(key)
    }

    override suspend fun bind(key: String, configuration: StorageConfiguration): ObjectBackupStorage =
        bindSelected(key, select(configuration))

    override suspend fun <T> withStorage(
        key: String,
        configuration: StorageConfiguration,
        block: suspend (ObjectStorageService) -> T,
    ): T {
        return withStorages(mapOf(key to configuration)) { block(it.getValue(key)) }
    }

    private suspend fun bindSelected(key: String, selected: StorageConfiguration): ObjectBackupStorage {
        require(key.isNotBlank()) { "Backup storage identity is required" }
        val address = when (selected.type) {
            "fs" -> ObjectBackupStorage(key = key, type = "fs", basePath = selected.basePath)
            "s3" -> ObjectBackupStorage(
                key = key, type = "s3", endpoint = selected.endpoint,
                bucket = selected.bucket, region = selected.region,
            )
            else -> error("Unsupported selected backup storage type")
        }
        return transaction {
            repository.add(address)
            val existing = repository.get(key)
            require(existing != null && existing.copy(created = address.created) == address) {
                "Backup storage identity or address conflicts with an existing binding: $key"
            }
            existing
        }
    }

    private suspend fun select(configuration: StorageConfiguration): StorageConfiguration = withContext(StorageDispatcher) {
        when (configuration.type) {
            "fs" -> {
                val path = requireNotNull(configuration.basePath) { "Backup filesystem base path is required" }
                require(path.isNotBlank()) { "Backup filesystem base path is required" }
                val selected = File(path).canonicalFile
                var ancestor: File? = selected
                while (ancestor != null && !ancestor.exists()) ancestor = ancestor.parentFile
                require(ancestor?.isDirectory == true) { "Backup filesystem location must be a directory" }
                configuration.copy(basePath = selected.path)
            }
            "s3" -> {
                require(!configuration.bucket.isNullOrBlank()) { "Backup S3 bucket is required" }
                require(!configuration.region.isNullOrBlank()) { "Backup S3 region is required" }
                val endpoint = requireNotNull(configuration.endpoint ?: System.getenv("AWS_ENDPOINT")) {
                    "Backup S3 endpoint is required"
                }
                configuration.copy(endpoint = normalizeEndpoint(endpoint))
            }
            else -> throw IllegalArgumentException("Unsupported backup storage type: ${configuration.type}")
        }
    }

    private fun normalizeEndpoint(endpoint: String): String {
        val uri = URI.create(endpoint).normalize()
        val scheme = uri.scheme?.lowercase()
        require((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            "Backup S3 endpoint must be an HTTP or HTTPS address"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Backup S3 endpoint must not include credentials, query parameters or fragments"
        }
        val port = when {
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        require(port == -1 || port in 1..65535) { "Backup S3 endpoint port is invalid" }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val host = uri.host.lowercase()
        val authority = if (port >= 0) "$host:$port" else host
        return URI.create("$scheme://$authority$path").toString()
    }
}
