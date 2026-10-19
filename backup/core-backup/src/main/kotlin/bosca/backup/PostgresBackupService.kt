package bosca.backup

import bosca.service.Service
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
}
