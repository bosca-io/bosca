package bosca.backup

import bosca.service.Service

/**
 * Manages per-revision progress using the caller's connection-manager context.
 *
 * Callers own exclusive payload-writer access and must verify destination persistence before
 * recording completion. Recover a running attempt only after its previous writer has stopped.
 */
interface ObjectBackupProgressService : Service {
    /**
     * Prepares operation-specific intent without resetting an existing revision's progress.
     * Backup preparation atomically reserves the retained destination path's length and digest;
     * all source revisions and scopes sharing that destination/path must describe the same bytes.
     * Restore target paths do not reserve immutable backup payloads.
     */
    suspend fun prepare(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress

    /**
     * Reserves a backup run's immutable manifest path independently of its payload checkpoints.
     * Matching run ownership is idempotent; payload claims or another run's reservation fail.
     * Call from the run preparation transaction so a failed preparation releases its reservation.
     */
    suspend fun reserveManifest(destinationId: String, path: String, runKey: String)

    /** Reads persisted progress without changing its state. */
    suspend fun get(key: String): ObjectBackupProgress?

    /**
     * Reads known intent by its exact revision identity without needing a precomputed digest.
     * No checkpoint is inserted or changed. Source/destination/scope, logical store, key and
     * revision are required; operation namespaces remain independent. Callers validate the
     * returned payload's immutable byte claims against their selected source observation.
     */
    suspend fun get(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        store: String,
        key: String,
        revision: String,
    ): ObjectBackupProgress?

    /** Reads the operation's exact revision intent without insertion; conflicting payload claims fail. */
    suspend fun get(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress?

    /** Lists one operation's progress within a source/destination scope. */
    suspend fun list(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupProgress>

    /** Starts a pending or failed record while checking the observed version. */
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupProgress

    /** Records completion after the caller verifies the stored payload. */
    suspend fun complete(key: String, expectedVersion: Long): ObjectBackupProgress

    /** Records a failed or interrupted attempt after its writer has stopped. */
    suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupProgress

    /** Marks completed output as failed after missing or corrupt bytes have been established. */
    suspend fun invalidate(key: String, expectedVersion: Long, error: String): ObjectBackupProgress
}
