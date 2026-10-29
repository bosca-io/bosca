package bosca.backup

import bosca.db.withConnectionManager
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies declared backup payloads from named source stores into one destination. */
class ObjectBackupTransfer(
    sources: Map<String, ManagedObjectStorageService>,
    private val destination: ManagedObjectStorageService,
) {
    private val sources = sources.toMap()

    /**
     * Copies a fixed inventory in order, preparing every object's progress before byte transfer.
     *
     * The caller supplies exact source lengths/digests and owns exclusive writer access to every payload
     * path for the operation. Failure or cancellation propagates, leaving unstarted items pending.
     * Repeated inventories reuse verified output. This method does not publish a manifest.
     */
    suspend fun copy(
        inventory: ObjectBackupManifest,
        destinationId: String,
        progressService: ObjectBackupProgressService,
    ): List<ObjectBackupProgress> {
        val captured = inventory.copy(objects = inventory.objects.toList())
        captured.validate()
        require(destinationId.isNotBlank()) {
            "Object backup destination identity is required"
        }

        for (entry in captured.objects) {
            require(entry.store in sources) {
                "No source object storage configured for store: ${entry.store}"
            }
        }

        val context = currentCoroutineContext()
        for (entry in captured.objects) {
            context.ensureActive()
            withConnectionManager {
                progressService.prepare(
                    ObjectBackupOperation.BACKUP, captured.sourceId, destinationId, captured.scope, entry,
                )
            }
        }

        return captured.objects.map { entry ->
            context.ensureActive()
            copy(captured.sourceId, destinationId, captured.scope, entry, progressService)
        }
    }

    /**
     * Copies a payload and persists its verified outcome using short database contexts.
     *
     * The caller owns exclusive writer access to [BackupObject.payloadPath], including other
     * scopes referencing that path, and must establish that any previous writer has stopped
     * before invoking this method. Source bytes must match the ordinary [copy] contract.
     * Invoke outside an active database transaction so storage I/O does not retain its connection.
     *
     * Completed output is verified before reuse. Missing or mismatching output is invalidated
     * before repair. Interrupted attempts restart and reconcile any already persisted bytes.
     * Storage failures and cancellation propagate even if recording the failure also fails.
     */
    suspend fun copy(
        sourceId: String,
        destinationId: String,
        scope: String,
        entry: BackupObject,
        progressService: ObjectBackupProgressService,
    ): ObjectBackupProgress = copy(
        sourceId, destinationId, scope, entry, progressService, entry.key, ObjectBackupOperation.BACKUP,
    )

    /** Uses a retained backup key when restoring an object to its original location. */
    internal suspend fun copy(
        sourceId: String,
        destinationId: String,
        scope: String,
        entry: BackupObject,
        progressService: ObjectBackupProgressService,
        sourceKey: String,
        operation: ObjectBackupOperation,
    ): ObjectBackupProgress {
        val prepared = withConnectionManager {
            progressService.prepare(operation, sourceId, destinationId, scope, entry)
        }

        val progress = when (prepared.status) {
            ObjectBackupProgressStatus.COMPLETED -> {
                val verified = withContext(Dispatchers.IO) { destination.hasVerifiedBackupPayload(entry) }
                if (verified) return prepared

                withConnectionManager {
                    progressService.invalidate(
                        prepared.key,
                        prepared.version,
                        "Destination payload is missing or does not match its declared length or digest",
                    )
                }
            }

            ObjectBackupProgressStatus.RUNNING -> withConnectionManager {
                progressService.fail(
                    prepared.key,
                    prepared.version,
                    "Previous transfer attempt stopped before acknowledgement",
                )
            }

            ObjectBackupProgressStatus.PENDING, ObjectBackupProgressStatus.FAILED -> prepared
        }

        val running = withConnectionManager {
            progressService.start(progress.key, progress.version)
        }
        return try {
            copy(entry, sourceKey)
            currentCoroutineContext().ensureActive()

            withConnectionManager {
                progressService.complete(running.key, running.version)
            }
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    withConnectionManager {
                        progressService.fail(
                            running.key,
                            running.version,
                            failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.name,
                        )
                    }
                }
            } catch (recordingFailure: Exception) {
                failure.addSuppressed(recordingFailure)
            }
            throw failure
        }
    }

    /**
     * Streams a declared source revision and verifies its persisted destination before returning.
     *
     * The caller configures source storage by [BackupObject.store], supplies its expected bytes,
     * and serializes writes to the immutable destination [BackupObject.payloadPath]. Existing
     * verified output is reused without opening the source. Missing or mismatching output is
     * copied and verified; other storage errors and cancellation propagate.
     *
     * Changed source bytes fail length/digest verification. Failed transfers may leave an
     * unverified payload for retry; they do not publish a manifest or a completed checkpoint.
     */
    suspend fun copy(entry: BackupObject): Unit = copy(entry, entry.key)

    private suspend fun copy(
        entry: BackupObject,
        sourceKey: String,
    ): Unit = withContext(Dispatchers.IO) {
        entry.validate()
        if (destination.hasVerifiedBackupPayload(entry)) return@withContext

        val source = requireNotNull(sources[entry.store]) {
            "No source object storage configured for store: ${entry.store}"
        }
        val context = currentCoroutineContext()
        val input = BackupPayloadInputStream(
            source.getInputStream(StringObjectPath(sourceKey)),
            entry,
            context,
        )
        input.use {
            val written = destination.setInputStream(StringObjectPath(entry.payloadPath), it, entry.length)
            it.verifyComplete()
            check(written == entry.length) {
                "Incomplete backup payload write: expected ${entry.length}, wrote $written"
            }
        }

        destination.verifyBackupPayload(entry)
    }
}
