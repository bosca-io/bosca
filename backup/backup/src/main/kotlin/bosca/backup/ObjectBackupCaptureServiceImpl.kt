package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StorageDispatcher
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Streams a selected retained revision into new independent backup storage and verifies it. */
@ServiceImplementation
class ObjectBackupCaptureServiceImpl(
    private val progressService: ObjectBackupProgressService,
) : ObjectBackupCaptureService {
    override suspend fun captureTracked(
        sourceId: String,
        destinationId: String,
        scope: String,
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): ObjectBackupProgress {
        require(sourceId.isNotBlank() && destinationId.isNotBlank() && scope.isNotBlank()) {
            "Object capture source, destination and scope identities are required"
        }
        val version = validateCapture(store, entry, source, destination, payloadPath)
        val known = withConnectionManager {
            progressService.get(ObjectBackupOperation.BACKUP, sourceId, destinationId, scope, store, entry.key, version)
        }
        if (known == null) {
            val captured = capture(store, entry, source, destination, payloadPath)
            currentCoroutineContext().ensureActive()
            return withConnectionManager {
                transaction {
                    val prepared = progressService.prepare(
                        ObjectBackupOperation.BACKUP, sourceId, destinationId, scope, captured,
                    )
                    check(prepared.status == ObjectBackupProgressStatus.PENDING && prepared.attempts == 0) {
                        "Capture checkpoint changed before acknowledgement: ${prepared.key}"
                    }
                    val running = progressService.start(prepared.key, prepared.version)
                    progressService.complete(running.key, running.version)
                }
            }
        }
        require(known.payload.length == entry.info.length) { "Conflicting captured revision length: ${known.key}" }
        val pending = when (known.status) {
            ObjectBackupProgressStatus.RUNNING -> error("Object capture is already running: ${known.key}")
            ObjectBackupProgressStatus.COMPLETED -> {
                val verified = withContext(StorageDispatcher) { destination.hasVerifiedBackupPayload(known.payload) }
                if (verified) return known
                withConnectionManager {
                    progressService.invalidate(
                        known.key, known.version,
                        "Captured payload is missing or does not match its recorded length or digest",
                    )
                }
            }
            ObjectBackupProgressStatus.PENDING, ObjectBackupProgressStatus.FAILED -> known
        }
        val running = withConnectionManager { progressService.start(pending.key, pending.version) }
        return try {
            ObjectBackupTransfer(mapOf(store to source), destination).copyVersion(known.payload, version)
            currentCoroutineContext().ensureActive()
            withConnectionManager { progressService.complete(running.key, running.version) }
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    withConnectionManager {
                        progressService.fail(
                            running.key, running.version,
                            failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.name,
                        )
                    }
                }
            } catch (recordingFailure: Exception) {
                if (recordingFailure !== failure) failure.addSuppressed(recordingFailure)
            }
            throw failure
        }
    }

    override suspend fun capture(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): BackupObject {
        val version = validateCapture(store, entry, source, destination, payloadPath)
        val path = StringObjectPath(payloadPath)
        var writeStarted = false
        try {
            return withContext(StorageDispatcher) {
                val exists = try {
                    destination.getObjectInfo(path)
                    true
                } catch (_: ObjectNotFoundException) {
                    false
                }
                check(!exists) { "Object capture payload already exists: $payloadPath" }
                currentCoroutineContext().ensureActive()
                val input = BackupPayloadInputStream(
                    source.getInputStream(StringObjectPath(entry.key), version),
                    entry.info.length,
                    payloadPath,
                    currentCoroutineContext(),
                )
                val sha256 = input.use {
                    currentCoroutineContext().ensureActive()
                    writeStarted = true
                    val written = destination.setInputStream(path, it, entry.info.length)
                    val digest = it.verifyComplete()
                    if (written != entry.info.length) {
                        throw BackupPayloadMismatchException("Incomplete capture write: $payloadPath")
                    }
                    digest
                }
                val captured = BackupObject(store, entry.key, version, entry.info.length, sha256, payloadPath)
                captured.validate()
                destination.verifyBackupPayload(captured)
                currentCoroutineContext().ensureActive()
                captured
            }
        } catch (failure: Throwable) {
            if (writeStarted) {
                try {
                    withContext(NonCancellable + StorageDispatcher) {
                        destination.delete(path)
                        val remains = try {
                            destination.getObjectInfo(path)
                            true
                        } catch (_: ObjectNotFoundException) {
                            false
                        }
                        check(!remains) { "Object capture cleanup left output: $payloadPath" }
                    }
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    private suspend fun validateCapture(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): String {
        require(store.isNotBlank() && entry.key.isNotBlank()) { "Object store and key are required" }
        val version = requireNotNull(entry.info.version?.takeIf { it.isNotBlank() }) {
            "A retained provider object version is required"
        }
        require(entry.key.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid capture object key: ${entry.key}"
        }
        require(payloadPath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid backup payload path: $payloadPath"
        }
        require(source !== destination) { "Backup storage must be independent of the source" }
        check(connectionOrNull()?.inTransaction != true) {
            "Object capture must run outside an active database transaction"
        }

        return version
    }
}
