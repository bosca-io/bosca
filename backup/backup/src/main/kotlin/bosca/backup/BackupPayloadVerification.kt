package bosca.backup

import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.currentCoroutineContext

/** Distinguishes missing or mismatching output from storage failures that must stop a transfer. */
internal suspend fun ObjectStorageService.hasVerifiedBackupPayload(entry: BackupObject): Boolean = try {
    verifyBackupPayload(entry)
    true
} catch (_: ObjectNotFoundException) {
    false
} catch (_: BackupPayloadMismatchException) {
    false
}

/** Reads and verifies a retained payload; callers run blocking storage reads on their I/O context. */
internal suspend fun ObjectStorageService.verifyBackupPayload(entry: BackupObject) {
    val input = BackupPayloadInputStream(
        getInputStream(StringObjectPath(entry.payloadPath)),
        entry,
        currentCoroutineContext(),
    )
    input.use {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (it.read(buffer) != -1) {
            // Reading to EOF verifies the declared length and SHA-256 digest.
        }
    }
}
