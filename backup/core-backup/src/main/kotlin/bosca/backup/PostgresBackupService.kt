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
     * The manifest also records known encryption-key names and populated encrypted tables from
     * that database view. Key values are never copied into metadata; availability, decryption and
     * dependencies of other modules require separate recovery validation.
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
     * Verifies the local artifact and checks the independently configured restore destination using
     * the same read-only PostgreSQL preflight as [restore], returning the verified manifest.
     * Requires the expected destination database name, libpq restore service and psql; pg_restore
     * and source capture configuration/services are not needed. The destination must be an empty
     * primary database compatible with the artifact's PostgreSQL major version, encoding and extensions.
     * Before opening a destination client, requires declared security.encryption.key configuration
     * and an explicit base64-encoded AES PIPELINE_SECRET_KEY environment value when declared.
     * Unsupported key requirements fail. Availability and encoding do not prove source-key identity.
     * Runs outside an active caller transaction on the storage dispatcher. Leaves the artifact and
     * destination data unchanged; cancellation stops the local client and cleans owned scratch.
     * The caller keeps the destination offline and excludes concurrent writers. This reserves
     * nothing: [restore] repeats the checks before writing. Asset coverage and full-instance
     * recovery requirements remain separate from this database-artifact preflight.
     */
    suspend fun preflightRestore(directory: Path): PostgresBackupManifest

    /**
     * Restores the native artifact and declared assets selected by a retained content [report].
     * Requires the verified local artifact in [directory] to match the report exactly. Rechecks
     * every saved reference against retained bytes; missing, corrupt, ambiguous or unresolved
     * references block writes, including gaps without an upload timestamp. Recorded report
     * counts are not current verification. Verifies every declared asset, destination conflicts
     * and the empty PostgreSQL target before writing anything, using the existing restore services.
     * Checks declared runtime-key availability before retained asset reads and repeats that check
     * before the native restore. Missing, unusable or unsupported requirements block destination writes;
     * matching configured keys to source ciphertext still requires application decryption validation.
     *
     * Copies assets to their original keys in [destination] through the shared checkpoints,
     * then restores the native database in one transaction. Native restore repeats its checks.
     * Needs no source database, content services, capture configuration or separate object manifest.
     * Runs on the storage dispatcher outside an active caller transaction. The caller owns immutable
     * local/retained input, independent backup storage, isolated offline destinations, exclusive
     * writers and a stable [destinationId]. Progress must live outside the database being replaced.
     * Stores remain open; failures/cancellation preserve input, copied assets and transfer progress.
     * After a failed asset copy or rolled-back database restore, retry can reuse verified assets.
     * A native commit may already have succeeded when an acknowledgement is lost: inspect the
     * destination before retrying; a populated database is never cleared or silently merged.
     *
     * Returns the restored native manifest. This materializes the selected components without
     * proving atomic database/object correspondence or publishing a complete recovery-point receipt.
     * Key identity, deployment rebinding and application activation remain separate.
     */
    suspend fun restoreContent(
        directory: Path,
        report: PostgresBackupContentReport,
        backup: ObjectStorageService,
        destination: ObjectStorageService,
        destinationId: String,
    ): PostgresBackupManifest

    /**
     * Downloads the report at [reportPath] and its native artifact from [backupStorageKey], then
     * invokes [restoreContent] with [destinationStorageKey] as the stable checkpoint destination.
     * The report path is its retained directory prefix, as recorded by contentReportPath receipts.
     * Both literal storage IDs select backup.objectStores; addresses bind atomically before either
     * store opens. They must be distinct and address independent storage. The existing PostgreSQL
     * restore configuration selects the empty database destination.
     *
     * Owns temporary downloads under backup.postgres.scratchDirectory, or the Java temporary
     * directory when unset. Requires an existing configured parent. Closes both stores and cleans
     * owned downloads before returning, including failure/cancellation; retained data, copied assets
     * and checkpoints remain. The caller provides immutable retained input, isolated offline targets,
     * exclusive writers and a journal outside the target database. Active caller transactions fail.
     * Retry uses the same IDs/report path to reuse verified assets. This is a direct service operation
     * with no completed-request replay: a failure during native acknowledgement or later close/cleanup
     * can follow a committed database. Inspect that target before retrying; populated targets fail.
     */
    suspend fun restoreContentConfigured(
        reportPath: String,
        backupStorageKey: String,
        destinationStorageKey: String,
    ): PostgresBackupManifest

    /**
     * Downloads the native artifact at [path] from the literal [backupStorageKey] entry in
     * backup.objectStores and restores it through [restore]. [path] is the retained directory
     * prefix from a native backup receipt. Verifies all declared components, including a saved
     * reference inventory, before PostgreSQL target access; this operation restores no assets.
     * Requires no source database, capture configuration/tools, content report or original files.
     *
     * Uses backup.postgres.restore for the empty database target and owns temporary downloads
     * under backup.postgres.scratchDirectory or the Java temporary directory. The configured
     * parent must exist. Binds the selected backup address before opening it, closes storage and
     * cleans owned downloads before returning, including failure/cancellation. Retained data stays
     * unchanged. Run outside an active caller transaction with immutable retained input, exclusive
     * offline target access and a recovery journal outside that target database.
     *
     * This has no completed-request replay. A native acknowledgement or later close/cleanup failure
     * may follow a committed database; inspect the target before retrying. Populated targets fail.
     * Object coverage, keys, deployment configuration and application activation remain separate.
     */
    suspend fun restoreConfigured(path: String, backupStorageKey: String): PostgresBackupManifest

    /**
     * Verifies [directory] and restores its native dump into the separately configured
     * backup.postgres.restore libpq service. Requires an explicit expected database name,
     * a fresh empty destination and exclusive offline access throughout preflight and restore.
     * Preflight requires the same encoding, no older server major version, and available default
     * extension versions matching the manifest. Source data and the manifest must be trusted.
     * Uses a single transaction without source database ownership, grants or tablespaces;
     * application permission rows remain ordinary data. Needs no source connection/configuration
     * and rejects an active caller transaction. Runtime credentials stay outside the artifact.
     * Rechecks declared key availability/encoding before destination clients; an earlier successful
     * [preflightRestore] does not authorize restoration after configuration changes. This does not
     * verify that available keys match the captured ciphertext.
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
