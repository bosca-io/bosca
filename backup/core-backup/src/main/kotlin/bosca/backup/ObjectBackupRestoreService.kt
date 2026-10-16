package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService

/** Materializes a retained object inventory using independent recovery storage. */
interface ObjectBackupRestoreService : Service {
    /**
     * Restores the manifest at [path] to its original logical store/key locations.
     *
     * Validates all mappings and retained payloads before writing destination assets. Matching
     * destination objects are reused. Conflicting objects are rejected unless this exact
     * checkpoint previously started writing them, allowing interrupted restores to resume.
     *
     * The caller owns isolated destination namespaces and exclusive writers, keeps [backup]
     * immutable and independent of [destinations], and supplies a stable [destinationId].
     * Different logical stores must address distinct destination namespaces or distinct keys.
     * Establish that any earlier restore writer has stopped before retrying its checkpoints.
     * Progress storage must remain outside any application database being replaced. Invoke
     * outside an active database transaction; storage I/O runs outside progress database contexts.
     * This restores only the declared objects, without restoring or validating a database.
     */
    suspend fun restore(
        path: ObjectPath,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest
}
