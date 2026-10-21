package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageService
import java.nio.file.Path
import java.time.OffsetDateTime

/** Manages PostgreSQL-native artifacts without replacing existing database backup operations. */
interface PostgresBackupService : Service {
    /**
     * Captures a native database artifact with saved content references into a new [directory],
     * selects and durably records those references, retains the native artifact, incrementally
     * captures selected assets, and exports/retains their inspected evidence report.
     * Uses the existing content capture service and checkpoints for one logical [store]/[scope].
     * Source writes continue; asset I/O starts after the database snapshot transaction ends.
     *
     * [prefix] contains databases/<capture-id>, objects/<capture-id>.json and reports/<capture-id>;
     * shared asset payloads use assets beneath that prefix. The report is also saved locally as
     * content-report.json. Each call captures a new point; after failure use a new local directory.
     * Matching verified asset checkpoints can be reused across points. [verifiedSince] controls
     * existing capture verification reuse; report export still performs fresh retained inspection.
     *
     * Requires an existing local parent, configured native capture, content/backup schemas,
     * independent caller-owned [source]/[backup], stable [destinationId], no active caller
     * transaction and exclusive destination writer access coordinated with other backup operations.
     * Stores stay open. After native capture succeeds, failures/cancellation preserve its local
     * artifact and retained progress; no report is acknowledged before all stages succeed.
     * A saved upload timestamp requires a VERIFIED retained asset. Missing, unresolved, ambiguous,
     * length-mismatched or corrupt uploaded references fail capture after the diagnostic report is
     * retained; the error identifies its retained path and the first affected reference. This check
     * uses the report's existing inspection pass without rereading assets. Soft deletion does not
     * remove an uploaded reference's requirement. References without upload evidence remain explicit
     * gaps without being classified as optional or covered. No upload timestamp is inferred for
     * historical metadata. Use [writeContentReport] to export diagnostics independently of capture.
     * This does not establish full required coverage, prove atomic database/object correspondence,
     * publish a completed recovery point, install a schedule or provide queued request recovery.
     */
    suspend fun captureContent(
        sourceId: String,
        directory: Path,
        store: String,
        scope: String,
        source: ObjectStorageService,
        destinationId: String,
        backup: ObjectStorageService,
        prefix: String,
        verifiedSince: OffsetDateTime? = null,
    ): PostgresBackupContentReport

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
     *
     * [onSnapshot] runs once before pg_dump in the exported read-only transaction, allowing
     * reference enumeration from the exact database view used by the dump. Queries must use
     * the current connection-manager context; caches and other connections are outside that view.
     * The callback must perform database reads only, without committing/rolling back, parallel
     * queries or asset I/O. Failure or cancellation prevents artifact publication and releases
     * the snapshot. Save the enumerated result only after capture succeeds. This callback does
     * not freeze object storage or prove that retained asset bytes match these database references.
     *
     * [includeContentReferences] also stages an inventory through the owning metadata/collection
     * services in that same view, before [onSnapshot]. It streams a JSON array of current,
     * historical and supplementary reference evidence, including deleted/pending records and gaps.
     * Its byte identity and record count are bound to the returned manifest. This requires Bosca's
     * content schema and services; ordinary database-only capture does not resolve those services.
     * Neither the inventory nor its successful transfer establishes retained asset coverage.
     */
    suspend fun capture(
        sourceId: String,
        directory: Path,
        includeContentReferences: Boolean = false,
        onSnapshot: suspend () -> Unit = {},
    ): PostgresBackupManifest

    /**
     * Reads manifest.json and verifies every declared local component's length and SHA-256.
     * A declared content-reference inventory must also decode as a JSON array with the recorded count.
     * Needs neither database/configuration access nor PostgreSQL tools. The caller owns the
     * directory and prevents concurrent modification. This checks artifact integrity, not
     * manifest authenticity or the destination's ability to restore extensions and other dependencies.
     */
    suspend fun verify(directory: Path): PostgresBackupManifest

    /**
     * Verifies a local database artifact and selects source metadata for its saved content references.
     * Resolves existing ID paths without source database access. Reads source metadata once per
     * distinct resolved path, without listing or opening asset bodies. Missing identities/objects
     * and mismatched recorded lengths are reported and excluded from the capture inventory.
     * Pending, deleted and historical evidence is preserved; no upload state is inferred.
     *
     * The returned inventory uses the database artifact's ID/source identity, the selected [scope],
     * one logical [store], and the source-observation start time. It can feed ObjectBackupCaptureService.
     * Selected entries need usable change observations; malformed metadata, backend failures,
     * callback errors and cancellation propagate. Caller-owned files stay immutable and [source]
     * stays open. Runs outside active caller transactions on the storage dispatcher.
     * [onReference] receives rows in saved order and may receive partial results before failure;
     * save the result only after successful return. Selection neither retains bytes nor establishes
     * complete coverage or an atomic database/object snapshot. Counts must accompany the inventory.
     */
    suspend fun selectContent(
        directory: Path,
        store: String,
        scope: String,
        source: ObjectStorageService,
        onReference: suspend (PostgresBackupContentSelection) -> Unit = {},
    ): PostgresBackupContentSelectionSummary

    /**
     * Inspects saved content-reference evidence against [objects] in the selected logical [store].
     * Verifies the local native artifact before retained asset I/O; both artifacts must identify the
     * same source. Resolves existing paths from saved IDs without accessing the source database,
     * services or objects. Includes pending, deleted and historical references without inferring
     * missing supplementary IDs. Multiple candidates for one key remain ambiguous.
     *
     * Reads selected retained payloads in [backup] to verify their full lengths/SHA-256, once per
     * distinct payload path. Reports missing/corrupt output; other storage failures and cancellation
     * propagate. Files, manifests and retained bytes must remain immutable throughout inspection.
     * Runs outside an active caller transaction on the storage dispatcher; storage is caller-owned.
     * [onReference] receives rows sequentially without retaining the full reference inventory. It may
     * observe partial results before a failure; save an inspection only after this method returns.
     * Verified retained integrity, upload times and lengths do not prove database snapshot coverage.
     * This neither writes storage nor publishes a complete recovery set.
     */
    suspend fun inspectContent(
        directory: Path,
        objects: ObjectBackupManifest,
        store: String,
        backup: ObjectStorageService,
        onReference: suspend (PostgresBackupContentInspection) -> Unit = {},
    ): PostgresBackupContentInspectionSummary

    /**
     * Exports a portable JSON evidence report to a new local [output] with an existing parent.
     * Verifies the local native artifact, its exact completed retained copy at [databasePath],
     * and the referenced retained assets in [backup]. Embeds both manifests, the saved capture
     * plan's [selectionCounts] and fresh inspection counts, including gaps. Both artifacts must
     * share capture/source identity; asset payloads use the single logical [store].
     *
     * Requires no source database, content services or source storage. Runs outside an active
     * caller transaction. Caller-owned files, manifests and backup data must remain immutable;
     * storage stays open and is never written. Existing output is never replaced. Failures and
     * cancellation before publication remove this attempt's temporary file. [onReference] may
     * observe partial results before failure. Successful export does not declare a complete
     * recovery point or prove that asset bytes match the database transaction boundary.
     */
    suspend fun writeContentReport(
        directory: Path,
        databasePath: String,
        objects: ObjectBackupManifest,
        selectionCounts: Map<PostgresBackupContentSelectionStatus, Long>,
        store: String,
        backup: ObjectStorageService,
        output: Path,
        onReference: suspend (PostgresBackupContentInspection) -> Unit = {},
    ): PostgresBackupContentReport

    /**
     * Reads and validates a local evidence report without database, configuration or storage
     * access. This checks the recorded evidence structure; it does not recheck retained bytes.
     */
    suspend fun readContentReport(file: Path): PostgresBackupContentReport

    /**
     * Retains a validated local evidence report as [prefix]/report.json in [backup]. The caller
     * owns immutable input, independent storage and exclusive writer access to this new relative
     * prefix, coordinating with other backup writers. Saves exact report intent before publication;
     * matching replay reuses existing output and can repair a partial report only with matching
     * intent. Conflicting reports, unsupported formats and unowned malformed output fail.
     * Report files cannot occupy declared database or asset component paths. Acknowledged writes
     * are read back and compared. Failure/cancellation leaves retained intent for retry, without
     * deleting backup data. Requires no active caller transaction; storage remains caller-owned.
     * This transfers recorded evidence, including gaps. It does not recheck referenced payloads,
     * refresh inspection counts, protect retention or publish a complete recovery point.
     */
    suspend fun uploadContentReport(file: Path, backup: ObjectStorageService, prefix: String): PostgresBackupContentReport

    /**
     * Retrieves a published retained report into a new local [file] with an existing parent.
     * Retained intent alone is insufficient. Needs no source, native tools or database access,
     * rejects active caller transactions and never replaces existing local output. Validates
     * report structure without fetching referenced database/asset bytes or refreshing counts.
     * Failure/cancellation before local publication removes owned temporary output; retained
     * storage is untouched and remains open. The caller owns immutable retained metadata and
     * exclusive access to the local destination. Successful retrieval is not recovery verification.
     */
    suspend fun downloadContentReport(backup: ObjectStorageService, prefix: String, file: Path): PostgresBackupContentReport

    /**
     * Verifies [directory] and restores its native dump into the separately configured
     * backup.postgres.restore libpq service. Requires an explicit expected database name,
     * a fresh empty destination and exclusive offline access throughout preflight and restore.
     * Preflight requires the same encoding, no older server major version, and available default
     * extension versions matching the manifest. Source data and the manifest must be trusted.
     * Uses a single transaction without source database ownership, grants or tablespaces;
     * application permission rows remain ordinary data. Needs no source connection/configuration
     * and rejects an active caller transaction. Runtime credentials stay outside the artifact.
     * Failures preserve the artifact and are propagated. Cancellation stops the native process;
     * a commit may already have succeeded, so inspect the destination before retrying. This
     * never drops/cleans an existing database and does not restore object storage or deployment state.
     */
    suspend fun restore(directory: Path): PostgresBackupManifest

    /**
     * Uploads a verified local artifact, including its declared inventory, into [backup] under [prefix].
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
     * Verifies all component lengths/digests, the native header, and any inventory's encoding/count
     * before publishing the local manifest.
     * The parent directory must exist; existing output is never replaced. Before publication,
     * failure/cancellation removes this attempt's local files and leaves retained storage untouched.
     * The caller owns storage, exclusive access to the selected prefix and destination directory,
     * and independent keys. This retrieves files only; it does not execute pg_restore.
     */
    suspend fun download(backup: ObjectStorageService, prefix: String, directory: Path): PostgresBackupManifest
}
