package bosca.backup

import bosca.db.connectionOrNull
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
class ObjectBackupCaptureServiceImpl : ObjectBackupCaptureService {
    override suspend fun capture(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): BackupObject {
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
}
