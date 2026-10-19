package bosca.backup

import bosca.service.Service

/** Executes native database capture and independent retention without worker-local retry state. */
interface PostgresBackupRunService : Service {
    /**
     * Runs one fixed [key]/[sourceId]/[storageKey]/[prefix] request using backup.objectStores.
     * Keys are 1–64 ASCII letters/digits/underscores/hyphens; prefixes are canonical relative paths.
     * A completed matching request returns its saved receipt without recapture or configuration/storage
     * I/O. The journal database must remain available.
     * Each unfinished attempt takes a fresh snapshot in owned local scratch and uses a new retained
     * path. Only successful capture, retained verification and store closure publish a receipt.
     * Abandoned retained attempts remain untouched; scratch is removed on ordinary exit/cancellation.
     * Uses the shared destination writer claim, rejecting active caller transactions and contention.
     * Process death retains ownership; [recover] is only for a writer known to have stopped.
     * Pipelines own retries. This captures database state only, not a matched database/object snapshot.
     */
    suspend fun backupConfigured(key: String, sourceId: String, storageKey: String, prefix: String): PostgresBackupReceipt

    /** Reads request state and its observed recovery token in the caller's database context. */
    suspend fun get(key: String): PostgresBackupRun?

    /** Lists one exact source/storage pair using ordinary offset pagination, with limit 1–100. */
    suspend fun list(sourceId: String, storageKey: String, offset: Long, limit: Int): List<PostgresBackupRun>

    /**
     * Atomically marks a stopped RUNNING request failed and releases only its observed writer token.
     * The caller must establish that the native process and storage writer stopped. Requires no
     * active caller transaction. Never expires, replaces or takes over a live writer automatically.
     */
    suspend fun recover(key: String, expectedToken: String)
}
