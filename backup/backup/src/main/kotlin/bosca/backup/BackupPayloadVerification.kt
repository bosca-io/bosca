package bosca.backup

import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.currentCoroutineContext
import java.time.OffsetDateTime

/** Reads the current retained identity without treating mutable version sentinels as proof. */
internal suspend fun ObjectStorageService.observeBackupPayload(entry: BackupObject): ObjectStorageInfo {
    val observed = getObjectInfo(StringObjectPath(entry.payloadPath))
    return observed.copy(version = observed.version?.takeIf { it.isNotBlank() && it != "null" })
}

/** Metadata-only checks preserve the age of the exact-version checksum verification. */
internal fun ObjectBackupProgress.canReuseVerification(
    observed: ObjectStorageInfo,
    verifiedSince: OffsetDateTime?,
): Boolean = verifiedSince != null && verifiedVersion != null &&
    verified?.isBefore(verifiedSince) == false &&
    observed.length == payload.length && observed.version == verifiedVersion

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

/** Verifies bytes addressed by the observed provider identity, rather than a later current version. */
internal suspend fun ObjectStorageService.verifyBackupPayload(entry: BackupObject, observed: ObjectStorageInfo) {
    if (observed.length != entry.length) {
        throw BackupPayloadMismatchException("Backup payload length mismatch: ${entry.payloadPath}")
    }
    val path = StringObjectPath(entry.payloadPath)
    val version = observed.version
    val stream = if (version == null) getInputStream(path) else getInputStream(path, version)
    BackupPayloadInputStream(stream, entry, currentCoroutineContext()).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) != -1) {
            // The stream verifies complete length and SHA-256 at EOF.
        }
    }
}
