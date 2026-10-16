package bosca.backup

import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads and publishes object inventories in a selected backup destination.
 *
 * The caller owns revision pinning, retention, and immutable destination payload paths.
 */
class ObjectBackupManifestStore(
    private val storage: ObjectStorageService,
    private val json: Json,
) {
    /** Verifies all referenced payloads before writing the manifest to [path]. */
    suspend fun save(path: ObjectPath, manifest: ObjectBackupManifest): Unit = withContext(Dispatchers.IO) {
        val inventory = manifest.copy(objects = manifest.objects.toList())
        inventory.validate()

        val key = path.toString()
        require(
            key.split('/').all {
                it.isNotEmpty() && it != "." && it != ".."
            }
        ) {
            "Invalid object backup manifest path: $key"
        }
        require(inventory.objects.none { it.payloadPath == key }) {
            "Manifest path conflicts with a payload: $key"
        }

        verifyPayloads(inventory)
        currentCoroutineContext().ensureActive()

        val bytes = json.encodeToString(ObjectBackupManifest.serializer(), inventory).toByteArray()
        val written = bytes.inputStream().use {
            storage.setInputStream(path, it, bytes.size.toLong())
        }
        check(written == bytes.size.toLong()) {
            "Incomplete manifest write: expected ${bytes.size}, wrote $written"
        }
    }

    /** Reads and validates inventory metadata without downloading the referenced payloads. */
    suspend fun load(path: ObjectPath): ObjectBackupManifest = withContext(Dispatchers.IO) {
        storage.getInputStream(path)
            .bufferedReader()
            .use {
                json.decodeFromString(ObjectBackupManifest.serializer(), it.readText())
            }
            .also {
                it.validate()
            }
    }

    /** Verifies the length and SHA-256 digest of every distinct referenced payload. */
    suspend fun verify(manifest: ObjectBackupManifest): Unit = withContext(Dispatchers.IO) {
        val inventory = manifest.copy(objects = manifest.objects.toList())
        inventory.validate()
        verifyPayloads(inventory)
    }

    private suspend fun verifyPayloads(manifest: ObjectBackupManifest) {
        val context = currentCoroutineContext()
        val verified = mutableSetOf<String>()

        for (entry in manifest.objects) {
            context.ensureActive()
            if (!verified.add(entry.payloadPath)) continue

            storage.verifyBackupPayload(entry)
        }
    }
}
