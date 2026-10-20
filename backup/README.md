# bosca-backup

Content backup and restore service for the Bosca platform. Provides table-level backup with manifest tracking, conflict resolution strategies, and GraphQL administration for managing backup and restore jobs. Handles PostgreSQL database content backup with configurable table definitions and restore execution.

## Prerequisites

- JDK 25

## Build

Run these commands from the **workspace root**. `backup` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :backup:core-backup:test :backup:backup:test  # Test contracts, backup and restore
```

## Recurring object-storage backups

Object capture supports ordinary unversioned S3-compatible and filesystem storage. Existing
metadata IDs, collection IDs and supplementary paths remain unchanged. Capture records source
change observations and verified retained bytes in backup data; it adds no content-location fields
and requires no provider versioning. Native database capture and object capture remain separate
operations without a combined restorable recovery point.

The **Back Up Object Storage** Pipeline action (`objectBackup.backup`) discovers source
objects, saves their observed metadata, and runs the incremental capture service.
It does not require a manually prepared inventory. Use the existing Pipeline schedules to
choose when it runs; the action installs no schedule and starts no production backup.

Configure independent source and backup IDs under `backup.objectStores`. In the action,
set a stable source identity, backup scope and destination storage ID. The **Source stores**
JSON setting maps logical names to configured IDs and literal prefixes, for example:

```json
{
  "content": { "storageKey": "source-content", "prefix": "" }
}
```

An empty prefix observes the whole configured source namespace. Include every namespace
needed for the declared scope. Keep the Pipeline's standard Input node, add this action,
and connect its manifest output to Output. Its optional `verifiedSince` input overrides
the checksum recheck setting; it can remain unconnected.

Each new backing job creates a distinct inventory and manifest. Retries of that job reuse
its saved selection and configured capture claim after worker restart. Completed object
checkpoints keep their verified payloads; interrupted repairs retry from their saved intent.
Other jobs continue to wait on that destination through the existing queue retry path.
A changed unversioned object that has not yet been
copied fails that selection; a new run observes its current contents.
Discovery uses offset listing and per-object metadata requests without downloading asset
bodies. Capture reads only new, changed or unfinished source objects; verified unchanged
payloads retain their existing paths. Older manifests and payloads remain available.

Ordinary runs compare retained metadata with the observation recorded at the last full
checksum check. Unchanged objects need no asset-body reads at either end. Metadata checks
preserve the recorded checksum time; they do not count as another integrity check.

Set **Checksum recheck interval (days)** to require full reads once that check is too old.
Omitting it uses metadata checks until an explicit integrity check. The cutoff derives from
the Pipeline's saved run time, so a delayed retry does not move it. New copies and restores
always verify full lengths and SHA-256 digests. Storage without a useful change observation
still requires full reads.

Unversioned source changes are observed through length, opaque entity tags and modification
times. An entity tag or modification time is required for incremental selection. Capture checks
the saved observation before and after ordinary object reads, hashes the actual bytes, and
verifies the retained copy. Reported changes or disappearance fail capture; changed bytes cannot
repair an older payload with a different digest. Metadata observations are not immutable source
versions or content hashes. Available retained provider versions are read directly.

This has been exercised with signed S3 SDK calls against an isolated local endpoint and with
filesystem storage, not a live Spaces installation. Source writes continue. Metadata checks and
pagination do not establish an atomic point-in-time snapshot or prove PostgreSQL recovery coverage.
It does not infer source deletions, remove retained payloads, or copy Bosca database content
between instances. Exact database/object reference association and retention remain open work.

## Restore object-storage backups

The **Restore Object Backup** Pipeline action (`objectBackup.restore`) reads a retained
manifest and restores its declared objects to their original keys in configured target
stores. Configure the independent backup and targets under `backup.objectStores`; the
source instance, source credentials and source storage configuration are not needed.

Set the action's **Backup storage ID**, **Restore destination identity** and **Target stores**
JSON mapping. Every logical store in the manifest needs a target, for example:

```json
{
  "content": "recovered-content",
  "artifacts": "recovered-artifacts"
}
```

Feed the relative saved manifest path into `manifestPath`. For a JSON Pipeline input,
connect Input through **To String** to that port, then connect the restored manifest output
to Output. The action emits a manifest only after the object restore run completes.

Preparation validates the selected addresses, reads only manifest metadata and saves the
fixed inventory and target mapping atomically. The first selected inventory remains bound
to that backing job and node across retries. A changed file at the manifest path cannot
silently select a different snapshot: execution compares it with the prepared inventory.
No target store is opened during preparation. Credentials remain in runtime configuration.

Execution uses the existing restore service: it verifies retained lengths and SHA-256
digests, rejects conflicting target data before asset writes, and reuses matching objects.
A recorded partial failure can retry after service restart without rewriting completed
objects. Redelivery of the same completed request returns its historical result without
storage work. A fresh request creates a distinct run that verifies current targets again,
reuses matching files and repairs missing owned output through the shared checkpoints.
Cancelled runs need the existing explicit retry control; a surviving running writer needs
stopped-writer recovery. The Pipeline action does not take over active writers or report
cancelled/incomplete outcomes as successful.

Use isolated target namespaces and keep restore progress outside any application database
being replaced. This action restores the declared objects only. PostgreSQL restoration,
matching database/object recovery points, deployment configuration, keys and whole-instance
recovery remain separate work. The local integration test captures through signed S3 SDK
reads, stops the source server, removes its catalog entry and restores through real queued
Pipeline jobs into independent files. Live cloud-provider and Kubernetes-cluster recovery
have not been exercised.

## Back up PostgreSQL through Pipelines

The **Back Up PostgreSQL** action (`postgresBackup.backup`) captures the configured primary
database and retains its verified native artifact in one queued operation. Set **Source identity**,
**Backup storage ID** (an entry under `backup.objectStores`) and **Backup prefix**. Keep the
Pipeline's Input node, connect the action's receipt output to Output, and use the existing
Pipeline run/schedule controls. No new schedule is installed.

Configure `backup.postgres.service` for that same primary database as described below. The
server and runner images provide PostgreSQL 18 clients. Optional `backup.postgres.scratchDirectory`
selects an existing writable parent directory; otherwise the Java temporary directory is used.
Each attempt needs local space for one database dump. It does not need shared scratch between
workers or ReadWriteMany storage.

A backing job and node identify one fixed request. Its source identity, storage ID and prefix
cannot change on retry. A completed request returns its recorded receipt without another dump
or object-store access; its journal database must still be available. A new Pipeline run creates
a new request. An unfinished attempt may capture a **new point in time** when retried on any
worker. The successful receipt records that attempt's actual database boundary, byte identity
and retained location. It never reports an abandoned attempt's boundary as completed.

Artifacts use a new `<prefix>/<requestKey>/<attemptId>` directory containing `intent.json`,
`database.dump` and, after retained verification, `manifest.json`. The receipt's `path` selects
that directory for `PostgresBackupService.download`. Local scratch is removed on ordinary exit
or cancellation. Incomplete remote attempts remain untouched and need explicit retention cleanup;
a process crash may also leave local scratch. A receipt is recorded only after storage closes.
Ordinary retries belong to Pipelines. A completed receipt records the successful attempt's outcome,
not a continuing audit of retained storage health.

Native requests share destination ownership with configured object backups, object restores and
capture plans. Contention fails without taking over another writer. Crashed writers retain their
claim; an administrator must establish that the process and storage writer stopped before recovery.
Inspect the request through the existing backup administration namespace:

```graphql
query {
  backups {
    postgresRun(key: "request-key") {
      key status attempts error writerToken
      result { storageKey path manifest { created completed length sha256 } }
    }
    postgresRuns(sourceId: "instance", storageKey: "retained", offset: 0, limit: 20) {
      key status
    }
  }
}
```

After stopping the observed writer, recover its exact token with
`backups { recoverPostgresRun(key: ..., expectedToken: ...) { key status } }` in a mutation,
then use the Pipeline's retry control. Tokens do not expire or authorize live-writer takeover.
All these operations require backup administration access. Runtime credentials stay in
configuration, outside job definitions and run records.

This action captures database state only. Matching database/object recovery points, protected
retention and keys, full-instance restore and deployment rebinding remain separate work.

## Native PostgreSQL database artifacts

`PostgresBackupService.capture(sourceId, directory)` creates a custom-format `database.dump`
and a final `manifest.json` in a new local directory. Its parent must already exist. This is
the database-artifact service used by the **Back Up PostgreSQL** Pipeline action described
above. Existing scheduled PostgreSQL backups and the legacy ZIP jobs are unchanged.

Configure the native client and a libpq connection service on the process invoking capture:

```yaml
backup:
  postgres:
    service: bosca-backup
    serviceFile: /run/secrets/pg_service.conf
    pgDump: /opt/postgresql/bin/pg_dump
```

`service` is required when capture is invoked. `serviceFile` is optional and otherwise
libpq uses its ordinary service-file lookup; `pgDump` defaults to `pg_dump` on PATH.
The service must connect to the same primary database as Bosca's connection pool.
Keep its authentication and TLS settings in runtime libpq configuration and mounted secrets.
The server JVM/native and runner images include PostgreSQL 18 clients on PATH. For local
JVM runs or custom images, install compatible clients or select their executable paths.
Missing configuration/tools fail on use, not at startup.

Capture exports a read-only REPEATABLE READ snapshot through the pool, passes it to
`pg_dump --snapshot`, and keeps the exporting transaction alive until the dump exits.
It captures the single database's schemas and data without the legacy table allowlist.
Ordinary inserts, updates and deletes continue; conflicting schema operations can make the
dump fail. Foreign tables need explicit data coverage and are rejected, as are standby
sources. Cluster-global roles/tablespaces, external data and object-storage bytes are outside
this artifact. PostgreSQL sequence values follow native dump semantics rather than an
MVCC history of each counter value.

The manifest records the source identity, database, exported/logical snapshot, source-clock
boundary observation and dump completion, PostgreSQL version/encoding, extension versions,
and the dump's byte length and SHA-256. The exported snapshot identifier cannot be reused
after capture ends. A complete custom-format file is checked and hashed before the final
manifest is published. Credentials and the libpq service configuration are not put in the
manifest. The dump itself contains sensitive database contents and needs independent storage,
encryption and keys before it is used as retained recovery data.

`verify(directory)` checks the manifest, regular payload file, length, SHA-256 and native
archive header without source access or installed database tools. The caller owns the directory
and prevents concurrent modification. Verification checks integrity, not authenticity or target
extension compatibility. Capture never replaces an existing output directory. Failures and
cancellation before publication stop the child process, roll back the exporting transaction and
remove the attempt's files. Published output remains complete if cancellation arrives afterward.
A process crash can leave partial output; stop any surviving dump and remove that incomplete
directory before a new capture. A dump needs local scratch space for the database artifact,
without bundling asset bytes.

The integration tests run real `pg_dump` and `pg_restore` with pgvector, restore after dropping
the source database, verify a different destination owner and application permission rows, and
prove that later source writes are excluded. Restoration uses `--no-owner --no-acl
--no-tablespaces` under a destination role with extension-creation privileges. Exact object
references, matching recovery-set publication, retention, deployment rebinding and a real
Kubernetes recovery drill remain open. See the PostgreSQL documentation for
[pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html),
[pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html) and
[libpq services](https://www.postgresql.org/docs/18/libpq-pgservice.html).

`PostgresBackupService.upload(directory, backup, prefix)` transfers the verified local
artifact through `ObjectStorageService` into independent storage. Use a new relative prefix
for each captured artifact, such as `postgresql/<manifest.id>`. Keep the local files immutable
and available until upload completes, and serialize writers to that prefix. Coordinate with
configured object runs that claim the same destination; this caller-owned transfer does not
acquire their destination claims. It does not open or close the supplied store.

Upload saves and reads back exact `intent.json` metadata before writing a new payload.
Matching retry verifies existing bytes and avoids uploading them again. The same verified
local artifact can repair owned missing/corrupt output; a different capture cannot replace it.
Unowned pre-existing payloads, changed intent and valid conflicting completion manifests fail.
The final `manifest.json` is written and read back only after retained length/SHA-256 verification.
A partial completion manifest can be repaired when exact intent survives. Malformed intent
or unsupported metadata needs inspection and a fresh prefix; it cannot authorize overwriting
unknown data. Failure/cancellation preserves unfinished retained data for retry.

`download(backup, prefix, newDirectory)` requires the completed manifest and streams its
dump into a new local directory. It verifies length, SHA-256 and the native header before
publishing the local manifest, without source database/configuration access or capture tools.
It rejects existing output and cleans up failed/cancelled downloads without deleting retained
data. `restore(newDirectory)` can then execute native restoration under the destination role.

The native integration test uploads through signed S3 SDK multipart calls, drops the source
database, removes the original local capture, downloads into new files and restores those files
through the service's native restore operation. This uses an isolated local S3-compatible endpoint,
not a live cloud provider.

`PostgresBackupService.restore(directory)` verifies the completed local artifact before any
connection to the destination. Configure its destination separately from capture:

```yaml
backup:
  postgres:
    restore:
      service: bosca-recovery
      database: recovered_bosca
      serviceFile: /run/secrets/recovery-pg-service.conf
      psql: /opt/postgresql/bin/psql
      pgRestore: /opt/postgresql/bin/pg_restore
```

Create the destination database beforehand, preferably from `template0`, under its intended
owner. Keep the application and other writers stopped on that destination throughout restore.
Source writes can continue. `service` and `database` are required on use. `serviceFile` uses
ordinary libpq lookup when omitted; `psql` and `pgRestore` default to the tools on PATH. Capture
configuration and source database access are unnecessary. The runtime images provide PostgreSQL
18 clients; custom runtimes need compatible tools. The destination role needs the privileges
required by the dump, including extension creation. The real pgvector test uses a separate
privileged destination role.

A read-only `psql` preflight checks the actual database name, writable primary, server major
version, encoding and extension defaults. The target must use the same encoding, a server
major version at least as new, and available default extension versions matching the manifest.
User schemas, public-schema objects, extra extensions, large objects and other checked
application state make it nonempty and prevent restoration. Keep exclusive destination access:
preflight and `pg_restore` use separate connections and do not fence concurrent writers.

Restore uses `--single-transaction --exit-on-error --no-owner --no-acl --no-tablespaces`.
It does not clean/drop existing data, create a database or apply source database grants.
Restored objects belong to the destination connection role; application permission rows remain
data. Native failures propagate, and a failure while loading data rolls back the transaction.
Cancellation stops the local client and removes temporary diagnostics without deleting the
artifact. Cancellation or lost acknowledgement may arrive after commit; inspect the destination
before retrying. A populated destination is rejected rather than automatically cleared/replayed.
Only restore trusted artifacts: checksums verify integrity, not source trust or authenticity.

The service still needs Pipeline/API/Studio/CLI orchestration, independently protected retention
and encryption, and a matching committed object inventory before it can form a complete snapshot.

## Architecture

`core-backup` defines the object backup service contracts and shared models. `backup` implements persistence, object transfers, manifest publication, GraphQL administration, and background jobs. Service implementations use `@ServiceImplementation` for generated DI registration.

`ObjectBackupCaptureService.capture` streams a selected source object into a new backup payload path, derives its SHA-256 digest, and verifies the retained bytes before returning a `BackupObject` for a manifest. It rejects existing output and active caller transactions. Failures and cancellation attempt cleanup, with cleanup failures attached to the original error. The caller owns independent immutable backup storage, exclusive writers, unique payload paths. Capture does not publish a manifest, track durable capture progress or establish database recovery coverage.

`captureTracked` records verified captures in the existing per-revision progress ledger. A later call or restarted service finds the known digest and reuses its recorded payload path without source reads, or repairs missing/corrupt output from the selected source against its recorded digest and saved observation. Known pending/failed checkpoints resume; running checkpoints require stopped-writer recovery. New completion and payload reservation commit atomically after verification. Before the first digest is known, failures have no durable pending checkpoint; an interrupted write or failed checkpoint commit can leave unreferenced output, requiring a fresh proposed path when no checkpoint committed.

Its required `verifiedSince` argument separates routine checks from full integrity checks.
A cutoff allows reuse when the saved destination observation still matches and the last
checksum check is recent enough. Entity tags and timestamps are change observations, not
content digests. Null forces a full read and checksum check. Metadata-only checks never
refresh the stored checksum time. Unversioned full checks compare metadata before and after
reading; observed changes prevent publication. Unknown observations get a full baseline check.

Verification observations are typed `ObjectStorageInfo` values in the existing progress
record. The registered V16 migration replaces the provider-only field, preserves prior
provider verification times, and leaves unverified historical records without invented proof.
Apply V16 with old backup writers stopped and run matching application/schema versions;
ordinary source content writes can continue.
GraphQL checkpoint `verified` exposes the last recorded full-check time, or null when unknown.
The Back Up Object Storage action uses metadata checks by default; its optional interval or
cutoff requests periodic rechecks. Ordinary manifest `verify` always checks every payload in full.

`ObjectBackupManifestStore.saveTracked` publishes a captured inventory using the same explicit verification cutoff. Every revision must have an exact matching completed backup checkpoint for the source, destination and scope before asset I/O begins. Shared payloads need one metadata/full check, while every associated checkpoint remains required. Full checks refresh the saved destination observation and checksum time; metadata-only checks preserve their age. Missing/corrupt assets invalidate their completed checkpoints and prevent publication. Other storage failures and cancellation propagate without invalidating prior evidence. Checkpoint versions are checked again before writing the manifest, and database contexts end before storage I/O. Call outside an active transaction with the same independent storage and exclusive-writer guarantees as capture. Ordinary `save` and `verify` always read payloads in full. The inventory capture flow and Back Up Object Storage action use this publication path.

`ObjectBackupCaptureService.captureInventory` captures and publishes an explicit `ObjectBackupCaptureInventory` selection. The core model carries identity, scope, creation time and observed object metadata grouped by logical store. The service snapshots caller collections, validates every selection/mapping and known checkpoint before asset I/O, then processes revisions in store/key/version order. Unknown revisions receive new unique paths under the selected payload prefix; known revisions reuse their recorded paths. Successful entries remain available after partial failure, cancellation or a failed manifest write, so retry/restart reads only unfinished source revisions. The same explicit verification cutoff applies to capture and publication. Supplied stores remain caller-owned.

The caller retains this selection and keeps uncopied selected sources readable, provides stable source/destination identities, independent retained storage, exclusive writers and an owned manifest path. This operation accepts a fixed selection; it does not discover or durably close a live source listing, record pending intent before the first unknown digest, establish PostgreSQL recovery coverage or schedule recurring captures. An interrupted first checkpoint commit can leave unreferenced output; retry selects a fresh path when no checkpoint committed.

`ObjectBackupCaptureService.prepareInventory` persists that selection and its destination identity, payload prefix and manifest path as an immutable `ObjectBackupCapturePlan`. It canonicalizes entry order and participates in the caller's database transaction. Repeating matching intent returns the original plan; changing its selection or output locations fails. Preparation opens no storage and creates no transfer checkpoints. `getPlan` and scope-filtered `listPlans` use the caller's database context and ordinary offset pagination. The registered migration creates an empty plan table and preserves historical progress without inventing selections.

The key-based `captureInventory` overload reloads saved intent in a short database context and delegates to the existing capture/publication flow. A restarted service can resume partial work or retry publication using the key and caller-owned stores. `verifiedSince` is chosen for each execution; it is not frozen in the plan. Plans record selected input, while the existing revision ledger records copy progress. Preparing a plan does not bind storage addresses, retain source versions, claim writer ownership, dispatch jobs or establish database recovery coverage. Those existing caller requirements still apply.

`ObjectBackupCaptureService.configureStorage` fixes a plan's logical source mapping to existing bound IDs. Its independent backup ID is the plan's destination identity. Configuration uses the caller's transaction, opens no stores and returns `ObjectBackupCaptureStorage`; `getStorage` reads that mapping. Matching or concurrent replay preserves the saved record, while changed mappings, missing bindings and source/backup aliasing fail atomically. The migration preserves existing plans without inventing storage mappings. Multiple logical stores may intentionally share one bound source namespace.

`captureConfigured` resolves the saved IDs from explicit configurations or selected `backup.objectStores` entries and delegates to capture/publication. The existing storage service checks every selected address before opening any store and closes all operation-scoped stores on success, failure or cancellation. Unrelated configurations are ignored. Credentials remain runtime-only and can rotate while the saved addresses stay fixed. Restart and retry preserve the plan, mapping and verified revision checkpoints. The integrity cutoff remains explicit for each execution. Call outside an active transaction and keep selected sources readable, retain stable storage accounts and own the output namespaces. The Back Up Object Storage action creates selections through `discoverConfigured` and invokes this execution path.

Configured capture shares destination ownership with configured backup and restore runs in the existing `object_store_writers` table. Each execution acquires a fresh token before opening any store and holds it until every opened store closes. Contention, including duplicate execution of the same plan, raises `ObjectBackupDestinationBusyException` before asset I/O. Independent destinations can proceed and source writes continue. Success, failure and cancellation release only that execution's token in short database contexts. A release failure propagates; when capture also failed, it is suppressed on the original failure. Per-revision progress still supports retry without recopying completed source revisions.

Ownership survives a process crash or a failed release. Read `ObjectBackupCaptureService.getWriter(planKey)` and, after establishing that the writer stopped, call `releaseWriter(planKey, observed.key)` in a database context. Recovery participates in the caller's transaction and rejects missing or stale tokens, so an old cleanup cannot delete a replacement claim. This releases destination ownership only; any running per-revision checkpoints still require the existing stopped-writer progress recovery. There is no expiry or automatic takeover. Apply the capture-writer migration with captures from older versions stopped; existing backup/restore ownership is preserved. Exclude writers outside this progress database, including unconfigured capture operations, from the retained destination.

The **Capture Object Backup** Pipeline action (`objectBackup.capture`) executes a saved, configured plan. Connect its required `planKey` string input and, optionally, `verifiedSince` as an ISO offset timestamp string. Omitted or JSON-null cutoffs verify retained payloads in full; malformed timestamps fail. The action calls `captureConfigured` using runtime `backup.objectStores` configuration and emits the typed published `ObjectBackupManifest`. Its generated metadata and serializers join the existing Backup registrar in both API server and runner. A JSON pipeline input can feed the key through the existing To String bridge.

Durable runs park on the framework's ordinary `ExecuteNodeInJob` child and resume through its staged output and parent completion callback. Backing execution checks the run identity with the same `verifyHasSaGroup` authorization as backup mutations; scoped execution contexts also require the existing token scope. Failures and cancellation propagate to the existing job machinery. Durable backing retries use queue policy; the node's inline retry settings do not replace it. A retry reloads the saved plan and reuses completed revision checkpoints. Dry runs trace supplied intent without service calls, storage access or an invented manifest. The action adds no schedule and does not establish a closed live inventory or PostgreSQL recovery coverage; stopped-writer recovery still applies.

Queued object backup, saved capture and restore actions wait five seconds when a destination
has another writer, then retry through the existing queue. Waiting preserves the job identity
and failure budget, records no failed node attempt and emits no output. Inline callers receive
the original destination-busy error. Destination ownership stays with its current writer;
persisted claims from a crashed worker still require stopped-writer recovery.

`ObjectBackupRestoreService` restores a verified object manifest from independent backup storage to configured logical stores, preserving original object keys. It checks all retained payloads and target conflicts before writing assets, reuses matching files, and resumes interrupted writes through the existing progress service. Callers provide isolated destination namespaces, exclusive writers, and a progress database outside any application database being replaced. Object restore does not restore a PostgreSQL database or establish database recovery-point coverage.

Its `inspect` method verifies the retained payloads and reports each destination object as missing, matching, repairable from an earlier attempt, or conflicting. Inspection does not write assets or create/update progress. Restore repeats the inspection when it runs.

`ObjectBackupRunService.prepareRestore` records a fixed inventory and its input manifest path. `executeRestore` compares the stored manifest with that intent, then restores the selected inventory and records its outcome. Backup and restore operations have separate run and checkpoint identities, filtered histories, and last-success queries. Restore runs reuse the existing partial-result, cancellation, retry and frozen-result behavior; progress storage remains separate from any application database being restored.

Execution rejects a run that is already running before storage access or attempt changes. Recovery requires establishing that the earlier writer stopped, then recording failure through `ObjectBackupRunService.fail` with the observed version before another execution. Configured runs coordinate their bound write destinations in the shared progress database. Callers exclude writers that do not participate in these claims, including writers using another progress database.

Read-only GraphQL queries under `backups.objectRuns` provide operation-filtered `list` and `lastSuccessful` reads and `run(key)` lookup. Run fields include the fixed inventory, paginated `items`, live `summary` and frozen `result`. List, item and inventory-object pages default to 20 entries and are capped at 100. These queries use the existing backup administrator/group and API-token scope checks. `storage(key)` reads a saved address, `dispatch(key, version)` reads one version's saved queue request, and `run.storage` exposes the fixed logical-store mapping. Missing bindings, requests and unconfigured mappings return null.

GraphQL mutations under `backups.objectRuns` accept typed portable inventories through `prepare` and `prepareRestore`. Preparation records immutable run intent and exact checkpoints atomically. Repeating the same intent returns the existing run, including any recorded outcome. Preparation requires backup administration access; it does not capture or pin source revisions, open storage, dispatch transfers, or establish a database recovery point.

After preparation, `bindStorage(keys)` binds selected IDs from `backup.objectStores` without opening storage. `configureStorage(key, backupKey, stores)` fixes the run's mapping using typed `{ store, storageKey }` entries; duplicate logical stores are rejected. Call these as separate operations before `dispatch(key, expectedVersion)`, which records a durable request for that pending version. The same controls support backup sources and restore targets. Configuration and credentials remain application-owned.

Dispatch returns its stable queue `key`, the `runKey`, pinned `version` and creation time. Read `run(key: runKey)` for execution progress. Query `dispatch(key: runKey, version: version)` to retrieve the original request after the run advances. A dispatch response acknowledges saved intent; the background worker performs storage work. All controls and lookups use the existing administrator and API-token scope checks.

`retry(key, expectedVersion)` returns a failed, partial or cancelled run to pending. It keeps the fixed inventory, storage mapping, attempt count and per-object checkpoints, clears the prior run error/result, and advances the version. It does not enqueue work; dispatch the returned version separately. Completed checkpoints remain available for incremental execution. `cancelPending(key, expectedVersion, reason)` cancels only the observed pending version and freezes its current totals without changing checkpoints. The state and version predicate is part of the database update, so a concurrent start prevents cancellation. Existing dispatch requests remain readable, and their stale deliveries cannot start a later retry. Active writers require the existing stopped-writer recovery contract.

Tracked backup preparation reserves each retained destination/path for one length and SHA-256 digest, and each manifest path for its owning run. Matching inventories can share payload bytes across sources and scopes. Conflicting payload claims and manifest/path collisions fail before transfer and roll back preparation. The migration backfills existing backup progress and runs and rejects conflicting historical claims without selecting one. Database references protect reservations while checkpoints or runs use them. Restore target paths keep their separate semantics; destination identities must consistently identify the same storage namespace.

`ObjectStorageServiceFactory` in core storage creates independently configured stores. `ContentConfiguration` uses the injected factory for the shared application store and closes it at application shutdown. `withStorage` closes operation-scoped stores when the callback ends, including failure or cancellation.

`ObjectBackupStorageService` records an immutable configured location for each backup storage identity. It rejects changed locations and duplicate address identities; credentials, URL presentation and bucket creation settings are not persisted. Its `withStorage` captures and checks the location in a short database context, then opens that exact configuration through the factory. `withStorages` checks all selected locations atomically before opening any and closes the opened stores if later setup or work fails. Active caller transactions are rejected before binding or opening storage. Filesystem paths resolve existing symlinks. S3 bindings require an explicit region and endpoint, with `AWS_ENDPOINT` supported as the endpoint fallback; credentials are supplied when opening and can rotate. The migration preserves existing inventories without inventing addresses for historical records. These checks identify configured addresses, not changed mounts, storage accounts or arbitrary endpoint aliases.

`ObjectBackupRunService.configureStorage` saves the independent backup storage ID and an exact logical-store mapping before the first run attempt. Mappings reference bound storage IDs and cannot change on retry. Restore destination IDs keep each logical store at the same location across later inventories, preserving shared checkpoint identity. Logical stores may share a restore location when their object keys are disjoint. `executeConfigured` opens the required caller-supplied configurations, reuses the existing transfer and verification behavior, and closes them after completion, failure or cancellation. Credentials are not saved with the run. The caller still pins source revisions and excludes destination writers that do not participate in these claims.

`backup.objectStores` supplies application-owned configurations by storage ID. `ObjectBackupStorageService.configurations(keys)` reads only the selected entries; IDs are literal map keys, including dots. Missing or malformed selected entries fail when used, while unrelated entries are ignored. `bindConfigured(keys)` validates all selected entries and binds their locations atomically in the caller's database context. Neither method opens a store, and configuration lookup does not access the database. There is no implicit fallback to `content.storage`.

`ObjectBackupRunService.executeConfigured(key)` resolves the saved storage IDs from this application configuration and uses the same scoped execution path as the explicit-configuration overload. Completed or cancelled history returns without catalog access, and an active run remains untouched. Credentials can rotate across application restarts while the location binding stays fixed. The API server and worker must receive matching locations for the selected IDs.

Starting a configured run atomically records ownership of every write destination with its run transition. Backup claims its retained store; restore claims each distinct mapped target. Another run using any claimed destination receives `ObjectBackupDestinationBusyException` without changing its state, version or attempts. Different destinations can proceed. Completion, recorded failure/partial failure, and cancellation release only that run's claims in the same transaction. Ownership survives process restarts and remains held until the prior writer stops and its outcome is recorded. Source writes continue.

Apply the writer-ownership migration with existing object-copy writers stopped. It records the destinations of already-running configured runs and rejects overlapping historical owners. Resolve conflicting records through the previous version's `ObjectBackupRunService.fail` after their writers stop, then retry the upgrade. After upgrade, a stopped run that remains `RUNNING` can be recovered with `fail` using its observed version. Unconfigured historical runs keep their caller-owned destination contract.

`ObjectBackupDispatchService.request(key, expectedVersion)` records a stable queue ID for one configured, pending run version and publishes through the existing backup queue. Repeating that request reuses its ID. The caller's transaction owns preparation, configuration and dispatch; rollback removes the request and delivery, while commit precedes publication. If publication fails after commit, the transaction layer logs it and the durable request remains available for recovery. A request response acknowledges saved intent, not worker completion.

`ObjectBackupRunJob` contains only the run key and expected version. Its generated helper and `ObjectBackupRunExecutor` use the existing backup queue. The worker checks the pinned pending version before configuration lookup and again atomically when starting the attempt. An old delivery cannot start a later retry or rerun a failed attempt. The queue's per-run executor lock serializes duplicate deliveries. Destination contention delays the job for five seconds without consuming a run attempt or job failure. Ordinary failures and cancellation propagate; the run records its outcome, and another attempt requires explicit retry and a new dispatch request.

`ObjectBackupDispatchService.recover(offset, limit)` republishes a page of requests whose exact run version is still pending, using `enqueueIfAbsent` with the original queue ID. It preserves existing queue state and excludes changed, running and terminal versions. Recovery uses a short database context, rejects an active caller transaction, and propagates publication failure or cancellation.

`ObjectBackupDispatchRecoveryJob` is a fallback for interrupted queue publication after a dispatch request commits. It scans pages on the existing common queue, independently of backup workers, and preserves queue retry state without restarting failed or cancelled run versions. The API server's maintenance package installs its enabled, nonconcurrent daily schedule (`0 0 * * *`) without catch-up runs. Existing disabled or edited schedules are preserved. Each sweep starts at offset zero; requests skipped when completed runs shrink the pending list are revisited by a later sweep. Recovery uses the selected job page size, defaulting to 100. Both applications already configure the common and backup runners and the scheduler; their existing enable settings still apply.

Storage's `getObjectInfo(path)` provides lightweight length, timestamp and available provider identifiers without fetching asset bodies. `listObjects(prefix, offset, limit)` discovers literal object keys and listing metadata through existing offset pagination, without per-object requests. These capabilities support source capture and destination checks. Listing positions can shift while source writes continue, and metadata alone is not a digest or retained revision. Ordinary transfer/publication verification still reads and verifies payloads. Associating database recovery points with complete retained object coverage remains separate work.

Storage also provides `getInputStream(path, version)` for exact retained S3 versions and Google generations, with no current-content fallback. Filesystem storage uses ordinary reads and modification metadata. Capture uses exact reads when a retained provider version is selected; unversioned capture instead checks its saved observation before and after an ordinary read. Neither path establishes database recovery coverage. Existing run transfer still reads caller-pinned source keys; capture orchestration is not yet connected to run preparation.

A configuration failure before the attempt leaves the run pending. If that job exhausts its queue retries, correct configuration, then use `cancelPending`, `retry` and `dispatch` with each returned version to obtain a new queue ID. A crashed `RUNNING` writer retains its ownership and still requires stopped-writer recovery. Studio/CLI actions and recurring backup creation remain separate work.

For example, application configuration can declare a source filesystem and independent S3-compatible retained storage. Supply credentials through the existing environment substitution:

```yaml
backup:
  objectStores:
    content:
      type: fs
      urlPrefix: ""
      basePath: "${CONTENT_STORAGE_PATH}"
    retained:
      type: s3
      urlPrefix: ""
      bucket: "${BACKUP_BUCKET}"
      region: "${BACKUP_REGION}"
      endpoint: "${BACKUP_ENDPOINT}"
      accessKeyId: "${BACKUP_ACCESS_KEY_ID}"
      accessKeySecret: "${BACKUP_ACCESS_KEY_SECRET}"
```

ZIP backup and restore operations run as jobs (`BackupJob`/`RestoreJob`) using an executor pattern. Each backup records a `BackupManifest` with metadata about backed-up tables and records. Restore operations use a configurable `ConflictStrategy` for handling existing data. `BackupTableDefinition` describes which tables to include and how to serialize them. GraphQL controllers provide admin access to backup and restore operations.

## Dependencies

- `io.bosca:core-backup` -- object backup service contracts and shared models
- `io.bosca:core` -- Bosca Core (platform framework)
- `io.bosca:core-content` -- Bosca Core Content (content model interfaces)
- `io.bosca:core-storage` -- Bosca Core Storage (object storage abstraction)
- `io.bosca:core-security` -- Bosca Core Security (authentication)
- `io.bosca:content` -- Bosca Content (content implementation)
- `io.bosca:storage` -- Bosca Storage (storage implementation)
- `io.bosca:sharedqueue` -- Bosca SharedQueue (job queue)

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":backup:backup"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
