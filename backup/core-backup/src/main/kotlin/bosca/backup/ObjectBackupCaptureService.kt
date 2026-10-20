package bosca.backup

import bosca.service.Service
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService
import java.time.OffsetDateTime

/** Captures selected source objects into independent storage without requiring provider versioning. */
interface ObjectBackupCaptureService : Service {
    /**
     * Observes configured source prefixes using listings and metadata-only lookups,
     * then atomically saves the selected inventory and storage mapping. Matching retries return
     * the saved selection without rediscovery; changed intent fails. Partial discovery never
     * publishes a plan. Requires no active caller transaction and independent retained storage.
     * Source writes continue, so this is an observation over time, not a closed database snapshot.
     * Unversioned selections require an entity tag or modification time. Capture checks those
     * observations before and after reading current bytes. Selected provider versions, when
     * available, are read directly. Missing or changed source objects fail capture.
     */
    suspend fun discoverConfigured(request: ObjectBackupDiscoveryRequest): ObjectBackupCapturePlan

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
     * Without [checkpointKey], claims survive a process crash and need stopped-writer
     * recovery through [releaseWriter].
     * Requires no active caller transaction, stable storage accounts, readable selected sources
     * and exclusion of writers outside this progress database. [verifiedSince] remains an
     * explicit execution-time cutoff. A failed ownership release is loud and preserves the claim;
     * if capture also failed, the release failure is suppressed on that original failure.
     * A queued job may supply its stable [checkpointKey] to resume its own interrupted capture.
     * The caller serializes deliveries of that job. Completed object checkpoints are retained;
     * unfinished selected checkpoints are retried. Another job's claim is never resumed.
     */
    suspend fun captureConfigured(
        key: String,
        configurations: Map<String, StorageConfiguration>,
        verifiedSince: OffsetDateTime?,
        checkpointKey: String? = null,
    ): ObjectBackupManifest

    /**
     * Uses saved IDs from backup.objectStores with the same scoped storage/capture contracts.
     * Queued deliveries reuse [checkpointKey] to resume their saved capture and object progress.
     */
    suspend fun captureConfigured(
        key: String,
        verifiedSince: OffsetDateTime?,
        checkpointKey: String? = null,
    ): ObjectBackupManifest

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
     * Captures all selected objects and publishes their verified portable manifest.
     *
     * Snapshots and validates [inventory] and required [sources] before asset I/O. Results are
     * ordered by logical store, key and optional provider version. Completed entries survive partial
     * failure/restart and reuse their recorded paths. Unknown revisions use new paths under
     * [payloadPrefix]; the caller owns [manifestPath] and independent [destination] storage.
     * [verifiedSince] applies to both capture reuse and publication; null forces full checks.
     *
     * Unversioned sources must still match their saved observations when read; a changed or
     * missing source fails rather than substituting different content. Requires stable source and
     * destination identities, exclusive writers and no active caller transaction. No source writes are paused. This
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
     * selected source against the recorded digest. Unversioned repair also checks the saved
     * source observation before and after transfer. A running checkpoint is rejected;
     * the caller must establish that its writer stopped before recording failure and retrying.
     *
     * New captures use [capture] and atomically record completion after verification. A failed
     * checkpoint commit can leave unreferenced output: retry reads committed progress first,
     * otherwise use a fresh proposed path. Pending intent before the first unknown-digest capture
     * is not recorded. Existing storage isolation, retention and transaction restrictions apply.
     * [verifiedSince] permits metadata-only reuse when a recorded checksum verification is at
     * least that recent and the saved destination metadata still matches. Entity tags and
     * timestamps are change observations, not checksum evidence or immutable versions. Null
     * forces full verification. Destinations without useful change metadata still need full
     * reads. The caller chooses the recheck cutoff; metadata-only reuse never refreshes it.
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
     * Captures [entry] through its existing object key and returns a manifest-ready [BackupObject].
     *
     * Derives the SHA-256 digest while streaming, checks the exact declared length and EOF,
     * then verifies retained bytes before acknowledging the entry. Unversioned objects are read
     * normally, checking their selected length, entity tag and modification time before and after
     * copying. These observations detect reported changes; they do not freeze source bytes or
     * establish an atomic snapshot. A selected provider version is read directly when available.
     * Requires a nonblank logical [store], source change metadata, and canonical relative object
     * key and [payloadPath]. Existing output is rejected before opening the source.
     *
     * The caller owns independent immutable backup storage, exclusive writers and a unique new
     * payload path, including when separate services address the same backend. This method neither
     * reserves destinations globally nor associates objects with database references. Invoke outside
     * an active database transaction. Blocking I/O runs on the storage dispatcher; supplied stores remain caller-owned.
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
