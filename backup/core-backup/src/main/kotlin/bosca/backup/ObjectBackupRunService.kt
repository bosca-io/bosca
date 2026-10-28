package bosca.backup

import bosca.service.Service
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageService

/**
 * Tracks fixed object inventories using the caller's connection-manager context.
 * Execution and configured preparation own short database contexts around storage work.
 */
interface ObjectBackupRunService : Service {
    /**
     * Loads manifest metadata from [backupKey] in backup.objectStores and atomically prepares
     * restore intent with its fixed logical target mapping under [requestKey]. A fresh request
     * creates a distinct run that verifies current targets. Matching retries return the saved
     * selection without rereading the manifest. Validates all selected addresses
     * before opening the retained store; targets are not opened and asset bodies are not read.
     * Changed arguments or mappings fail; execution rejects a manifest that changed after selection.
     * Credentials stay in application configuration. Requires no active caller transaction and no access
     * to the source installation. This does not execute or verify the retained payloads; use
     * [executeConfigured] afterward. Complete database replacement before preparation and keep this progress database stable during execution.
     */
    suspend fun prepareRestoreConfigured(
        requestKey: String,
        destinationId: String,
        backupKey: String,
        path: String,
        stores: Map<String, String>,
    ): ObjectBackupRun

    /** Atomically prepares backup intent and its exact retained-payload checkpoint associations. */
    suspend fun prepare(inventory: ObjectBackupManifest, destinationId: String): ObjectBackupRun

    /** Atomically fixes restore input, its manifest path and exact destination object checkpoints. */
    suspend fun prepareRestore(
        inventory: ObjectBackupManifest,
        destinationId: String,
        path: String,
    ): ObjectBackupRun

    /** Reads a persisted run without changing its attempt or historical result. */
    suspend fun get(key: String): ObjectBackupRun?

    /**
     * Fixes the bound storage IDs for an untouched pending run in the caller's database context.
     *
     * Every declared logical store maps to a bound source for backup, or a bound target for restore.
     * [backupKey] identifies independent retained storage and matches a backup run's destination ID.
     * Repeating identical intent is idempotent; changed mappings and conflicting restore keys fail.
     * Restore destination IDs keep each logical store at the same location across later runs,
     * because those inventories share destination checkpoints.
     */
    suspend fun configureStorage(key: String, backupKey: String, stores: Map<String, String>): ObjectBackupRunStorage

    /** Reads the fixed storage IDs in the caller's context, without credentials or opening stores. */
    suspend fun getStorage(key: String): ObjectBackupRunStorage?

    /**
     * Executes the run through its saved mapping and the required caller-supplied configurations.
     *
     * Captures configuration inputs, validates/binds all selected stores before opening any, and
     * closes them after work, failure or cancellation. Unrelated configurations are ignored.
     * Completed/cancelled outcomes return without opening stores. A queued job may supply its
     * stable [checkpointKey] to resume its own running attempt and saved object checkpoints;
     * other running attempts are rejected. The caller serializes deliveries of that job.
     * Requires no active caller transaction. Configured runs in this progress database serialize
     * write destinations. The caller pins source revisions, preserves storage accounts and excludes
     * writers that do not participate in these claims, including other progress databases.
     * A successful BACKUP publishes [BackupCompleted] with its completion transaction;
     * completed backup replays may redispatch it. RESTORE never emits that event.
     * This does not dispatch an execution job.
     */
    suspend fun executeConfigured(
        key: String,
        configurations: Map<String, StorageConfiguration>,
        checkpointKey: String? = null,
    ): ObjectBackupRun

    /**
     * Executes through the saved mapping and selected backup.objectStores application configuration.
     * Uses the same storage, revision and writer contracts as the explicit-configuration overload.
     * Terminal/active outcomes are checked before configuration lookup; missing or malformed selected
     * entries fail before starting an attempt. [checkpointKey] enables the same job-owned
     * resumption as the explicit-configuration overload. Credentials remain outside the persisted run.
     */
    suspend fun executeConfigured(key: String, checkpointKey: String? = null): ObjectBackupRun

    /**
     * Executes the exact pending dispatch version using application configuration. With a stable
     * [checkpointKey], redelivery can resume that job's running attempt and existing object progress.
     * Its saved checkpoint binds both job identity and [expectedVersion], so an older dispatch
     * cannot resume a newer attempt even if supplied the same job identity. Other changed or
     * non-pending versions return current state without storage I/O. Checks repeat atomically
     * after configuration lookup. A recorded failed/cancelled attempt requires explicit [retry]
     * and a new dispatch request. The caller serializes deliveries of the job.
     */
    suspend fun executeConfigured(key: String, expectedVersion: Long, checkpointKey: String? = null): ObjectBackupRun

    /** Lists runs of one operation within a source/destination scope using offset pagination. */
    suspend fun list(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupRun>

    /** Returns the operation's latest completed inventory, separately from newer partial attempts. */
    suspend fun lastSuccessful(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
    ): ObjectBackupRun?

    /** Lists the run's current shared checkpoints in declared inventory order. */
    suspend fun items(key: String, offset: Long, limit: Int): List<ObjectBackupProgress>

    /** Reads current checkpoint health; terminal outcome totals remain frozen in the run's result. */
    suspend fun summary(key: String): ObjectBackupRunSummary

    /**
     * Starts a pending or retryable terminal run using its observed version. Configured runs claim
     * all bound write destinations atomically; contention rolls back the run transition and every
     * claim. Ownership has no expiry and survives process restarts. Unconfigured runs retain their
     * caller-owned destination contract.
     */
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupRun

    /** Returns a failed, partial or cancelled run to pending without dispatching or resetting its checkpoints. */
    suspend fun retry(key: String, expectedVersion: Long): ObjectBackupRun

    /**
     * Cancels only the observed pending version, recording [reason] and its current checkpoint totals.
     * Checks state and version atomically with the transition; a concurrent start prevents cancellation.
     * Does not stop an active writer, change checkpoints or remove historical dispatch requests.
     */
    suspend fun cancelPending(key: String, expectedVersion: Long, reason: String): ObjectBackupRun

    /**
     * Acknowledges an inventory only when all required checkpoints are completed.
     * Callers verify restored objects or publish and verify backup manifests before this transition.
     * Releases only this run's destination claims atomically with the recorded outcome.
     */
    suspend fun complete(key: String, expectedVersion: Long): ObjectBackupRun

    /** Records failure/partial outcome and releases owned destinations after the earlier writer stops. */
    suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupRun

    /** Cancels a pending run, or records a stopped writer's cancellation, releasing its destinations. */
    suspend fun cancel(key: String, expectedVersion: Long, error: String): ObjectBackupRun

    /**
     * Transfers and publishes an inventory outside an active database transaction.
     *
     * [sources] addresses the declared logical stores, and [destination] matches the run's
     * destination identity. The caller pins source revisions, owns exclusive payload/manifest
     * writer access and establishes that any prior run writer has stopped.
     * Execution rejects [ObjectBackupRunStatus.RUNNING] before storage access. Once the previous
     * writer stops, record its failure through [fail] with the observed version before another attempt.
     * Completed/cancelled runs return their historical result. Cancelled runs require [retry];
     * use a new inventory identity for a new copy of completed content.
     */
    suspend fun execute(
        key: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
    ): ObjectBackupRun

    /**
     * Restores the exact prepared inventory through independent backup storage.
     *
     * Requires a restore run and compares its stored input manifest before destination writes.
     * Uses the same isolated-destination and immutable-backup contract as
     * [ObjectBackupRestoreService.restore]. Progress storage remains outside any application
     * database being replaced. Earlier writers must have stopped before recovery. Completed or
     * cancelled redelivery returns the recorded outcome; cancelled runs require explicit [retry].
     * Execution rejects [ObjectBackupRunStatus.RUNNING] before storage access. Once the previous
     * writer stops, use [fail] with the observed version before executing another attempt.
     */
    suspend fun executeRestore(
        key: String,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
    ): ObjectBackupRun
}
