package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageService
import java.nio.file.Path

/** Captures and transfers standalone native PostgreSQL artifacts. Object recovery is separate. */
interface PostgresBackupService : Service {
    /**
     * Creates a new [directory] containing a custom-format database.dump and verified manifest.json.
     * Uses the configured libpq service and an exported read-only repeatable-read snapshot while
     * source writes continue. The caller owns the output and invokes outside an active transaction.
     * Credentials stay in runtime configuration. Failures/cancellation remove unpublished files.
     *
     * Published metadata includes known encryption-key names, never key values. Capture does not
     * back up object storage or prove that recovery keys match the source ciphertext.
     */
    suspend fun capture(
        sourceId: String,
        directory: Path,
    ): PostgresBackupManifest

    /** Verifies the local manifest, native dump header, length and SHA-256 without contacting a database. */
    suspend fun verify(directory: Path): PostgresBackupManifest

    /**
     * Verifies the artifact and configured empty restore destination without changing its data.
     * Checks PostgreSQL/encoding/extension compatibility and declared key availability. The caller
     * excludes destination writers. This reserves nothing; [restore] repeats its checks.
     */
    suspend fun preflightRestore(directory: Path): PostgresBackupManifest

    /**
     * Downloads a selected retained artifact from backup.objectStores into owned temporary scratch,
     * then restores the separately configured empty PostgreSQL destination. Closes storage and cleans
     * scratch on exit. Requires immutable backup input and exclusive offline target access.
     * A lost acknowledgement may follow a database commit; inspect the target before retrying.
     */
    suspend fun restoreConfigured(path: String, backupStorageKey: String): PostgresBackupManifest

    /**
     * Verifies and restores the native dump into backup.postgres.restore using pg_restore and a
     * single transaction. The caller provisions an empty compatible destination and excludes writers.
     * Preserves application ACL rows while omitting source ownership/grants/tablespaces. Required
     * key availability does not prove decryption. Leaves object storage and deployment state alone.
     */
    suspend fun restore(directory: Path): PostgresBackupManifest

    /**
     * Uploads verified local files into an independently retained [prefix]. The caller owns immutable
     * input and exclusive destination access. Matching saved intent permits reuse/repair; completion
     * is published only after retained verification. Failure preserves retryable retained intent.
     */
    suspend fun upload(directory: Path, backup: ObjectStorageService, prefix: String): PostgresBackupManifest

    /**
     * Downloads a completed retained artifact into a new local directory, verifying its native header,
     * length and digest before publication. Needs no source database or PostgreSQL tools. The caller
     * owns immutable retained input; failed unpublished downloads are cleaned without changing backup.
     */
    suspend fun download(backup: ObjectStorageService, prefix: String, directory: Path): PostgresBackupManifest
}
