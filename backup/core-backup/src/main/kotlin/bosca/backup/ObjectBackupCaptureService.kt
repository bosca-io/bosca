package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService
import java.time.OffsetDateTime

/** Creates verified retained payloads from explicitly selected provider revisions. */
interface ObjectBackupCaptureService : Service {
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
