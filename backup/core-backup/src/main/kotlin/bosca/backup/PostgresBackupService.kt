package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageService
import java.nio.file.Path

/** Captures PostgreSQL-native artifacts without replacing existing database backup operations. */
interface PostgresBackupService : Service {
    /**
     * Creates a new [directory] with a custom-format database.dump and a final manifest.json.
     * Uses the primary connection pool to export a read-only repeatable-read snapshot and
     * backup.postgres.service to connect pg_dump to that same database through libpq.
     * The exported snapshot remains alive until the dump exits; ordinary writes continue.
     * Requires no active caller transaction and an existing parent directory. Existing output
     * is never replaced. Before publication, failures/cancellation remove this attempt's files and
     * release the snapshot. Published output remains complete if cancellation arrives afterward.
     * Process death may leave incomplete output; stop any surviving dump before removing it.
     * Credentials remain in runtime libpq configuration. This captures database state only;
     * matching object revisions, independent retention and whole-instance recovery are separate.
     */
    suspend fun capture(sourceId: String, directory: Path): PostgresBackupManifest

    /**
     * Reads manifest.json and verifies the complete local database.dump length and SHA-256.
     * Needs neither database/configuration access nor PostgreSQL tools. The caller owns the
     * directory and prevents concurrent modification. This checks artifact integrity, not
     * manifest authenticity or the destination's ability to restore extensions and other dependencies.
     */
    suspend fun verify(directory: Path): PostgresBackupManifest

    /**
     * Uploads a verified local artifact into independently retained [backup] under [prefix].
     * The caller owns immutable local files and exclusive writer access to this relative prefix,
     * coordinating with configured runs that claim the same storage destination. New uploads save
     * exact manifest intent before payload writes. Matching saved intent or completion metadata
     * authorizes verification/reuse or repair of the same bytes. Different intent and unowned
     * existing payloads fail. A partial completion manifest can be repaired only with exact intent;
     * unsupported formats and valid conflicting completion manifests fail.
     * The completion manifest is published only after retained length/SHA-256 verification.
     * Requires no active transaction. Leaves unfinished retained intent for retry on failure or
     * cancellation, without deleting backup data. Storage remains caller-owned. This does not
     * establish encryption, retention policy or object coverage.
     */
    suspend fun upload(directory: Path, backup: ObjectStorageService, prefix: String): PostgresBackupManifest

    /**
     * Downloads a completed retained native artifact into a new local [directory]. Requires no
     * source database, capture configuration or PostgreSQL tools, and no active caller transaction.
     * Verifies the full length, digest and native header before publishing the local manifest.
     * The parent directory must exist; existing output is never replaced. Before publication,
     * failure/cancellation removes this attempt's local files and leaves retained storage untouched.
     * The caller owns storage, exclusive access to the selected prefix and destination directory,
     * and independent keys. This retrieves files only; it does not execute pg_restore.
     */
    suspend fun download(backup: ObjectStorageService, prefix: String, directory: Path): PostgresBackupManifest
}
