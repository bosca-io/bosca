package bosca.backup

import bosca.service.Service

/** Executes standalone database capture and independent retention through existing job checkpoints. */
interface PostgresBackupRunService : Service {
    /**
     * Executes a fixed source/storage/prefix request and retains a verified native artifact.
     * Dispatches [BackupCompleted] with the completion transaction after stores close.
     * Replaying a completed request may redispatch its event without taking another dump.
     * Completed matching replay returns its saved receipt. An unfinished retry takes a fresh database
     * snapshot in owned scratch and writes a new retained attempt; it cannot reuse a dead snapshot.
     * The selected backup store closes and scratch is cleaned before a receipt is recorded.
     *
     * A queued caller supplies its stable [checkpointKey] to resume its own interrupted request.
     * Redelivery rotates the execution token; callers serialize deliveries. Other callers use
     * explicit stopped-writer [recover]. Runs outside caller transactions and uses the existing
     * destination claim. Pipelines own retries. No object-content capture or schedule is added.
     */
    suspend fun backupConfigured(
        key: String,
        sourceId: String,
        storageKey: String,
        prefix: String,
        checkpointKey: String? = null,
    ): PostgresBackupReceipt

    /** Reads request state and its observed recovery token in the caller's database context. */
    suspend fun get(key: String): PostgresBackupRun?

    /** Lists one exact source/storage pair with ordinary offset pagination and limit 1–100. */
    suspend fun list(sourceId: String, storageKey: String, offset: Long, limit: Int): List<PostgresBackupRun>

    /**
     * Marks a stopped running request failed and releases its observed writer token atomically.
     * The caller must establish that the process/writer stopped and have no active transaction.
     */
    suspend fun recover(key: String, expectedToken: String)
}
