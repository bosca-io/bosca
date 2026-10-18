package bosca.backup

import bosca.service.Service
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService
import java.time.OffsetDateTime

/** Creates verified retained payloads from explicitly selected provider revisions. */
interface ObjectBackupCaptureService : Service {
    /** Reads this plan's active configured writer in the caller's database context, without storage I/O. */
    suspend fun getWriter(key: String): ObjectBackupCaptureWriter?

    /**
     * Releases only the observed execution token in the caller's database transaction.
     * The caller must establish that the writer stopped before recovering a persisted claim.
     * Missing or stale ownership fails; this never expires or takes over an active writer.
     * This releases destination ownership only and does not reset per-revision progress.
     */
    suspend fun releaseWriter(key: String, expectedKey: String)

    /**
     * Fixes this plan's logical source bindings in the caller's database transaction.
     * Every selected logical store maps to an existing bound ID. The independent backup ID
     * is the plan's destination identity. Matching replay is idempotent; changed intent fails.
     * This performs no storage I/O and neither claims writer ownership nor starts capture.
     */
    suspend fun configureStorage(key: String, stores: Map<String, String>): ObjectBackupCaptureStorage

    /** Reads saved storage identities in the caller's context, without credentials or storage I/O. */
    suspend fun getStorage(key: String): ObjectBackupCaptureStorage?

    /**
     * Opens only this plan's saved IDs from caller-supplied configurations and captures its input.
     * Checks every bound address before opening any store, and closes all stores on completion,
     * failure or cancellation. Unrelated configurations are ignored; credentials are not persisted.
     * Holds a token-fenced destination claim, shared with configured backup and restore runs,
     * from before the first store opens until all stores close. Contention fails before asset I/O.
     * Claims survive a process crash; recover only a stopped writer through [releaseWriter].
     * Requires no active caller transaction, stable storage accounts, retained source versions
     * and exclusion of writers outside this progress database. [verifiedSince] remains an
     * explicit execution-time cutoff. A failed ownership release is loud and preserves the claim;
     * if capture also failed, the release failure is suppressed on that original failure.
     */
    suspend fun captureConfigured(
        key: String,
        configurations: Map<String, StorageConfiguration>,
        verifiedSince: OffsetDateTime?,
    ): ObjectBackupManifest

    /** Uses only saved IDs from backup.objectStores with the same scoped storage/capture contracts. */
    suspend fun captureConfigured(key: String, verifiedSince: OffsetDateTime?): ObjectBackupManifest

    /**
     * Records a canonical selected inventory and output intent in the caller's database context.
     * Matching replay returns the existing plan; changed intent for the same identity fails.
     * Participates in the caller's transaction and performs no storage access or dispatch.
     */
    suspend fun prepareInventory(
        inventory: ObjectBackupCaptureInventory,
        destinationId: String,
        payloadPrefix: String,
        manifestPath: String,
    ): ObjectBackupCapturePlan

    /** Reads immutable capture intent in the caller's database context without opening stores. */
    suspend fun getPlan(key: String): ObjectBackupCapturePlan?

    /** Lists selected plans in one exact scope using the caller's context and offset pagination. */
    suspend fun listPlans(
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupCapturePlan>

    /**
     * Loads saved intent in a short database context, then captures/publishes it through [sources].
     * Uses the same storage/retention/exclusive-writer contracts as inventory-based capture.
     * Rejects active caller transactions. The caller chooses [verifiedSince] for this execution;
     * plans neither freeze a recheck interval nor record an execution outcome or dispatch a job.
     */
    suspend fun captureInventory(
        key: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
        verifiedSince: OffsetDateTime?,
    ): ObjectBackupManifest

    /**
     * Captures all explicitly selected revisions and publishes their verified portable manifest.
     *
     * Snapshots and validates [inventory] and required [sources] before asset I/O. Results are
     * ordered by logical store, key and provider revision. Completed entries survive partial
     * failure/restart and reuse their recorded paths. Unknown revisions use new paths under
     * [payloadPrefix]; the caller owns [manifestPath] and independent [destination] storage.
     * [verifiedSince] applies to both capture reuse and publication; null forces full checks.
     *
     * Source revisions must remain readable. Requires stable source/destination identities,
     * exclusive writers and no active caller transaction. No source writes are paused. This
     * neither discovers a closed snapshot, persists initial unknown-digest intent, establishes
     * database recovery coverage nor schedules later captures. Supplied stores remain caller-owned.
     */
    suspend fun captureInventory(
        inventory: ObjectBackupCaptureInventory,
        destinationId: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
        payloadPrefix: String,
        manifestPath: String,
        verifiedSince: OffsetDateTime?,
    ): ObjectBackupManifest

    /**
     * Captures or reuses a revision with a durable completed checkpoint in the existing ledger.
     *
     * Uses stable [sourceId], [destinationId] and [scope] identities. Known revisions retain their
     * recorded payload path, even if a different new [payloadPath] is proposed. Completed bytes
     * are verified without opening the source; missing/corrupt known output is repaired from the
     * selected provider version against the recorded digest. A running checkpoint is rejected;
     * the caller must establish that its writer stopped before recording failure and retrying.
     *
     * New captures use [capture] and atomically record completion after verification. A failed
     * checkpoint commit can leave unreferenced output: retry reads committed progress first,
     * otherwise use a fresh proposed path. Pending intent before the first unknown-digest capture
     * is not recorded. Existing storage isolation, retention and transaction restrictions apply.
     * [verifiedSince] permits metadata-only reuse when a recorded checksum verification is at
     * least that recent and current length/provider version still match. Null forces full
     * verification. Stores without a stable provider version always receive full verification.
     * The caller chooses the periodic recheck cutoff; metadata-only reuse never refreshes it.
     */
    suspend fun captureTracked(
        sourceId: String,
        destinationId: String,
        scope: String,
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
        verifiedSince: OffsetDateTime?,
    ): ObjectBackupProgress

    /**
     * Captures [entry]'s retained provider version and returns a manifest-ready [BackupObject].
     *
     * Derives the SHA-256 digest while streaming, checks the exact declared length and EOF,
     * then verifies retained bytes before acknowledging the entry. Requires a nonblank logical
     * [store] and provider version, and canonical relative object key and [payloadPath]. Existing
     * output is rejected before opening the source. No current-content fallback is permitted.
     *
     * The caller owns independent immutable backup storage, exclusive writers and a unique new
     * payload path, including when separate services address the same backend. Provider retention
     * must keep the selected revision readable. This method neither reserves destinations globally
     * nor associates object revisions with database references. Invoke outside an active database
     * transaction. Blocking I/O runs on the storage dispatcher; supplied stores remain caller-owned.
     *
     * Failure or cancellation attempts to delete newly attempted output while preserving the
     * original failure and attaching cleanup failures, including output remaining after deletion.
     * A process interruption can leave unreferenced output. Returning an entry does not publish
     * a manifest or establish snapshot consistency.
     */
    suspend fun capture(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): BackupObject
}
