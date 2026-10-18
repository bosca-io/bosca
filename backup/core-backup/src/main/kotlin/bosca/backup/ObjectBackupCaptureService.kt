package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService

/** Creates verified retained payloads from explicitly selected provider revisions. */
interface ObjectBackupCaptureService : Service {
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
