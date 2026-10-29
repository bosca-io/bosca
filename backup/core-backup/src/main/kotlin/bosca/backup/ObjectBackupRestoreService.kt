package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectPath
import bosca.storage.service.ManagedObjectStorageService

/** Materializes a retained object inventory using independent recovery storage. */
interface ObjectBackupRestoreService : Service {
    /**
     * Validates the manifest and inspects selected destination objects without writes.
     *
     * Returns existing progress without creating intent or changing its state. Different destination bytes are
     * reported per object. Retained payloads needed for missing/different files are verified;
     * matching target bytes need no retained-body download. Invalid manifests/mappings and
     * unavailable/corrupt required payloads fail explicitly. Inspection does not reserve destinations or establish database coverage.
     * [restore] checks storage again when it executes; the same isolation requirements apply.
     */
    suspend fun inspect(
        path: ObjectPath,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupRestoreInspection

    /**
     * Inspects a selected inventory without reading a separate manifest location.
     * Copies and validates [inventory], verifies
     * required retained bytes and reads current targets/progress under the same rules as path-based
     * inspection. An earlier inspection does not replace these
     * checks. No intent, progress or destination data is written. This checks declared objects;
     * database recovery and complete content-reference coverage remain separate.
     */
    suspend fun inspect(
        inventory: ObjectBackupManifest,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupRestoreInspection

    /**
     * Restores the manifest at [path] to its original logical store/key locations.
     *
     * Validates all mappings and required retained payloads before writing destination assets.
     * Matching destination objects are reused without downloading their backup bodies.
     * Missing and different selected files are restored, including files from an earlier recovery.
     * Files outside the manifest remain untouched. Interrupted restores resume through checkpoints.
     *
     * The caller owns isolated destination namespaces and exclusive writers, keeps [backup]
     * immutable and independent of [destinations], and supplies a stable [destinationId].
     * Different logical stores must address distinct destination namespaces or distinct keys.
     * Establish that any earlier restore writer has stopped before retrying its checkpoints.
     * Finish database replacement before recovery and keep progress storage stable during it. Invoke
     * outside an active database transaction; storage I/O runs outside progress database contexts.
     * This restores only the declared objects, without restoring or validating a database.
     */
    suspend fun restore(
        path: ObjectPath,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest

    /**
     * Restores a selected inventory without rereading a manifest location.
     *
     * Copies and validates the inventory, verifies its retained payloads and applies the same
     * isolation, progress and selected-file replacement rules as the path-based restore. Run execution
     * uses this entry point after comparing stored manifest input with its persisted intent.
     */
    suspend fun restore(
        inventory: ObjectBackupManifest,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest
}
