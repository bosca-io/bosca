package bosca.backup

import bosca.service.Service

/**
 * Manages per-revision progress using the caller's connection-manager context.
 *
 * Callers own exclusive payload-writer access and must verify destination persistence before
 * recording completion. Recover a running attempt only after its previous writer has stopped.
 */
interface ObjectBackupProgressService : Service {
    /** Prepares immutable intent without resetting an existing revision's progress. */
    suspend fun prepare(
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress

    /** Reads persisted progress without changing its state. */
    suspend fun get(key: String): ObjectBackupProgress?

    /** Reads exact revision intent without inserting it; conflicting immutable payload claims fail. */
    suspend fun get(
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress?

    /** Lists progress within one source/destination scope. */
    suspend fun list(
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
