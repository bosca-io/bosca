package bosca.backup

import bosca.service.Service

/** Executes native database capture and independent retention without worker-local retry state. */
interface PostgresBackupRunService : Service {
    /**
     * Executes combined native/reference/asset capture using [source]'s configured storage and
     * the independent backup [storageKey]. Source binding/scope are immutable request intent.
     * Uses the same writer claims, token fencing, stopped-writer recovery and optional queued
     * [checkpointKey] as [backupConfigured]. Every unfinished attempt takes a new database point;
     * verified matching assets can be reused. Both stores close and owned scratch is cleaned before
     * recording a receipt. After reclaiming a stopped request's destination, retry fails interrupted
     * object checkpoints only within its fixed source/destination/scope/logical store so repair can
     * restart with new checkpoint versions. Callers must exclude external destination writers before
     * resumption, just as for native stopped-writer recovery. The receipt's path downloads the native
     * artifact; contentReportPath retrieves its retained evidence report. Completed matching replay
     * needs only the journal.
     * Source writes continue. Report gaps remain explicit: request completion does not classify
     * required coverage or declare a complete database/object recovery point. Pipelines own retries;
     * this does not add a schedule or change runner behavior.
     */
    suspend fun backupContentConfigured(
        key: String,
        sourceId: String,
        source: PostgresBackupContentSource,
        storageKey: String,
        prefix: String,
        checkpointKey: String? = null,
    ): PostgresBackupReceipt

    /**
     * Runs one fixed [key]/[sourceId]/[storageKey]/[prefix] request using backup.objectStores.
     * Keys are 1–64 ASCII letters/digits/underscores/hyphens; prefixes are canonical relative paths.
     * A completed matching request returns its saved receipt without recapture or configuration/storage
     * I/O. The journal database must remain available.
     * Each unfinished attempt takes a fresh snapshot in owned local scratch and uses a new retained
     * path. Only successful capture, retained verification and store closure publish a receipt.
     * Abandoned retained attempts remain untouched; scratch is removed on ordinary exit/cancellation.
     * Uses the shared destination writer claim, rejecting active caller transactions and contention.
     * A queued caller may supply its stable [checkpointKey] to resume only that job's RUNNING
     * request after interruption. The caller serializes deliveries of the job. Resumption rotates
     * the execution token and takes a fresh snapshot at a new retained path; it cannot reuse a
     * dead worker's exported snapshot. Other jobs and unqueued callers retain the explicit
     * stopped-writer [recover] contract. Checkpoint identities must be nonblank and contain no colon.
     * [includeContentReferences] saves the owning content services' reference inventory in that
     * attempt's exported database view and retains it with the dump. It is part of fixed request
     * intent and cannot change on replay or retry. The default captures the database alone.
     * Pipelines own retries. Neither mode retains asset bytes or establishes a matched
     * database/object snapshot.
     */
    suspend fun backupConfigured(
        key: String,
        sourceId: String,
        storageKey: String,
        prefix: String,
        checkpointKey: String? = null,
        includeContentReferences: Boolean = false,
    ): PostgresBackupReceipt

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
