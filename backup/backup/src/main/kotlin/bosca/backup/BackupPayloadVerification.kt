package bosca.backup

import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.currentCoroutineContext
import java.time.OffsetDateTime

/** Reads the current retained identity without treating mutable version sentinels as proof. */
internal suspend fun ManagedObjectStorageService.observeBackupPayload(entry: BackupObject): ObjectStorageInfo {
    val observed = getObjectInfo(StringObjectPath(entry.payloadPath))
    return observed.copy(version = observed.version?.takeIf { it.isNotBlank() && it != "null" })
}

/** Compares change observations without treating entity tags or timestamps as content hashes. */
internal fun ObjectStorageInfo.matchesObservation(other: ObjectStorageInfo): Boolean {
    if (length != other.length) return false
    val selectedVersion = version?.takeIf { it.isNotBlank() && it != "null" }
    if (selectedVersion != null) return selectedVersion == other.version
    return etag?.takeIf { it.isNotBlank() } == other.etag?.takeIf { it.isNotBlank() } &&
        modified?.toInstant() == other.modified?.toInstant()
}

/** Reuses a recorded checksum check under the caller's policy without extending its age. */
internal fun ObjectBackupProgress.canReuseVerification(
    observed: ObjectStorageInfo,
    verifiedSince: OffsetDateTime?,
): Boolean {
    val checked = verifiedInfo ?: return false
    return verifiedSince != null && verified?.isBefore(verifiedSince) == false &&
        observed.length == payload.length &&
        (checked.version != null || !checked.etag.isNullOrBlank() || checked.modified != null) &&
        checked.matchesObservation(observed)
}

/** Distinguishes missing or mismatching output from storage failures that must stop a transfer. */
internal suspend fun ManagedObjectStorageService.hasVerifiedBackupPayload(entry: BackupObject): Boolean = try {
    verifyBackupPayload(entry)
    true
} catch (_: ObjectNotFoundException) {
    false
} catch (_: BackupPayloadMismatchException) {
    false
}

/** Reads and verifies a retained payload; callers run blocking storage reads on their I/O context. */
internal suspend fun ManagedObjectStorageService.verifyBackupPayload(entry: BackupObject) {
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

/** Verifies the declared bytes and checks that the observation stayed unchanged during the read. */
internal suspend fun ManagedObjectStorageService.verifyBackupPayload(entry: BackupObject, observed: ObjectStorageInfo) {
    if (observed.length != entry.length) {
        throw BackupPayloadMismatchException("Backup payload length mismatch: ${entry.payloadPath}")
    }
    val path = StringObjectPath(entry.payloadPath)
    val stream = getInputStream(path)
    BackupPayloadInputStream(stream, entry, currentCoroutineContext()).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) != -1) {
            // The stream verifies complete length and SHA-256 at EOF.
        }
    }
    if (!observed.matchesObservation(observeBackupPayload(entry))) {
        throw BackupPayloadMismatchException("Retained object changed during verification: ${entry.payloadPath}")
    }
}
