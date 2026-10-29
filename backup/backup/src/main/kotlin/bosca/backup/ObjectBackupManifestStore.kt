package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StorageDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/**
 * Reads and publishes object inventories in a selected backup destination.
 *
 * The caller owns source selection, retention, and immutable destination payload paths.
 */
class ObjectBackupManifestStore(
    private val storage: ManagedObjectStorageService,
    private val json: Json,
) {
    /**
     * Publishes using matching completed backup checkpoints in [progressService].
     *
     * [destinationId] must address this storage. [verifiedSince] permits metadata-only reuse
     * of a recent full checksum check while the saved destination observation still matches;
     * null forces full verification. Missing change metadata requires a full check.
     * Entity tags and timestamps are observations, not digests or immutable versions.
     * Metadata-only checks never extend the recorded checksum age.
     * Requires independent immutable storage, exclusive writers and no active transaction.
     * Reserves [path] for this exact inventory before publication; another inventory or a
     * retained payload cannot share it. Successful writes are read back and compared.
     * Missing/corrupt output invalidates its completed proof and prevents publication; other
     * storage failures and cancellation propagate without invalidating prior evidence.
     */
    suspend fun saveTracked(
        path: ObjectPath,
        manifest: ObjectBackupManifest,
        destinationId: String,
        progressService: ObjectBackupProgressService,
        verifiedSince: OffsetDateTime?,
    ): Unit = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) {
            "Tracked manifest publication must run outside an active database transaction"
        }
        require(destinationId.isNotBlank()) { "Object backup destination identity is required" }
        val inventory = validatedInventory(path, manifest)
        val checkpoints = withConnectionManager {
            inventory.objects.map { entry ->
                val checkpoint = checkNotNull(progressService.get(
                    ObjectBackupOperation.BACKUP, inventory.sourceId, destinationId, inventory.scope, entry,
                )) { "Completed capture checkpoint is missing: ${entry.store}/${entry.key}@${entry.revision}" }
                check(checkpoint.status == ObjectBackupProgressStatus.COMPLETED) {
                    "Capture checkpoint is not completed: ${checkpoint.key}"
                }
                checkpoint
            }
        }
        withConnectionManager {
            transaction {
                progressService.reserveManifest(
                    destinationId,
                    path.toString(),
                    objectBackupIdentity(
                        ObjectBackupOperation.BACKUP,
                        listOf("captured-manifest", Json.encodeToString(ObjectBackupManifest.serializer(), inventory)),
                    ),
                )
            }
        }
        val verified = mutableListOf<ObjectBackupProgress>()
        for (shared in checkpoints.groupBy { it.payload.payloadPath }.values) {
            currentCoroutineContext().ensureActive()
            verified += verifyTrackedPayload(shared, progressService, verifiedSince)
        }
        withConnectionManager {
            for (checkpoint in verified) {
                check(progressService.get(checkpoint.key) == checkpoint) {
                    "Capture checkpoint changed before manifest publication: ${checkpoint.key}"
                }
            }
        }
        write(path, inventory)
    }

    /** Verifies all referenced payloads before writing the manifest to [path]. */
    suspend fun save(path: ObjectPath, manifest: ObjectBackupManifest): Unit = withContext(Dispatchers.IO) {
        val inventory = validatedInventory(path, manifest)
        verifyPayloads(inventory)
        write(path, inventory)
    }

    private fun validatedInventory(path: ObjectPath, manifest: ObjectBackupManifest): ObjectBackupManifest {
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

        return inventory
    }

    private suspend fun write(path: ObjectPath, inventory: ObjectBackupManifest) {
        currentCoroutineContext().ensureActive()

        val bytes = json.encodeToString(ObjectBackupManifest.serializer(), inventory).toByteArray()
        val written = bytes.inputStream().use {
            storage.setInputStream(path, it, bytes.size.toLong())
        }
        check(written == bytes.size.toLong()) {
            "Incomplete manifest write: expected ${bytes.size}, wrote $written"
        }
        check(load(path) == inventory) { "Persisted object backup manifest differs from its selected inventory: $path" }
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

    private suspend fun verifyTrackedPayload(
        shared: List<ObjectBackupProgress>,
        progressService: ObjectBackupProgressService,
        verifiedSince: OffsetDateTime?,
    ): List<ObjectBackupProgress> {
        val payload = shared.first().payload
        val observed = try {
            val info = storage.observeBackupPayload(payload)
            if (shared.all { it.canReuseVerification(info, verifiedSince) }) return shared
            storage.verifyBackupPayload(payload, info)
            info
        } catch (failure: ObjectNotFoundException) {
            invalidate(shared, progressService, failure)
        } catch (failure: BackupPayloadMismatchException) {
            invalidate(shared, progressService, failure)
        }
        return withConnectionManager {
            transaction {
                shared.map { checkpoint ->
                    progressService.recordVerification(checkpoint.key, checkpoint.version, observed)
                }
            }
        }
    }

    private suspend fun invalidate(
        shared: List<ObjectBackupProgress>,
        progressService: ObjectBackupProgressService,
        failure: Exception,
    ): Nothing {
        try {
            withContext(NonCancellable) {
                withConnectionManager {
                    transaction {
                        for (checkpoint in shared) {
                            progressService.invalidate(
                                checkpoint.key, checkpoint.version,
                                "Captured payload is missing or does not match its recorded length or digest",
                            )
                        }
                    }
                }
            }
        } catch (recordingFailure: Exception) {
            if (recordingFailure !== failure) failure.addSuppressed(recordingFailure)
        }
        throw failure
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
