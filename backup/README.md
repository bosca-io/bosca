# bosca-backup

Content backup and restore service for the Bosca platform. Provides table-level backup with manifest tracking, conflict resolution strategies, and GraphQL administration for managing backup and restore jobs. Handles PostgreSQL database content backup with configurable table definitions and restore execution.

## Prerequisites

- JDK 25

## Build

Run these commands from the **workspace root**. `backup` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :backup:core-backup:test :backup:backup:test  # Test contracts, backup and restore
```

## Architecture

`core-backup` defines the object backup service contracts and shared models. `backup` implements persistence, object transfers, manifest publication, GraphQL administration, and background jobs. Service implementations use `@ServiceImplementation` for generated DI registration.

`ObjectBackupCaptureService.capture` streams a selected provider revision into a new backup payload path, derives its SHA-256 digest, and verifies the retained bytes before returning a `BackupObject` for a manifest. It rejects existing output and active caller transactions. Failures and cancellation attempt cleanup, with cleanup failures attached to the original error. The caller owns independent immutable backup storage, exclusive writers, unique payload paths and provider retention. Capture does not publish a manifest, track durable capture progress or establish database recovery coverage.

`captureTracked` records verified captures in the existing per-revision progress ledger. A later call or restarted service finds the known digest and reuses its recorded payload path without source reads, or repairs missing/corrupt output from the exact provider version. Known pending/failed checkpoints resume; running checkpoints require stopped-writer recovery. New completion and payload reservation commit atomically after verification. Before the first digest is known, failures have no durable pending checkpoint; an interrupted write or failed checkpoint commit can leave unreferenced output, requiring a fresh proposed path when no checkpoint committed.

Its required `verifiedSince` argument controls retained-payload verification. Null always repeats the full length and SHA-256 check. A cutoff permits metadata-only reuse when the current retained provider version and length match a version fully verified at or after that cutoff. Metadata-only reuse leaves the original verification time unchanged. New or changed provider versions are checksum-verified through an exact version-addressed read before their proof is saved. Stores without stable version IDs, including filesystem storage, always require full reads; ETags and timestamps are not digest evidence. Callers choose the integrity-check interval and retain provider versions. The migration leaves historical checkpoints without verification proof until their next full check. Recurring capture orchestration remains separate work.

`ObjectBackupManifestStore.saveTracked` publishes a captured inventory using the same explicit verification cutoff. Every revision must have an exact matching completed backup checkpoint for the source, destination and scope before asset I/O begins. Shared payloads need one metadata/full check, while every associated checkpoint remains required. Full checks refresh provider proof; metadata-only checks preserve its age. Missing/corrupt assets invalidate their completed checkpoints and prevent publication. Other storage failures and cancellation propagate without invalidating prior evidence. Checkpoint versions are checked again before writing the manifest, and database contexts end before storage I/O. Call outside an active transaction with the same independent storage and exclusive-writer guarantees as capture. Ordinary `save` and `verify` always read payloads in full. This publication primitive is not yet connected to recurring inventory capture.

`ObjectBackupCaptureService.captureInventory` captures and publishes an explicit `ObjectBackupCaptureInventory` selection. The core model carries identity, scope, creation time and provider-version entries grouped by logical store. The service snapshots caller collections, validates every selection/mapping and known checkpoint before asset I/O, then processes revisions in store/key/version order. Unknown revisions receive new unique paths under the selected payload prefix; known revisions reuse their recorded paths. Successful entries remain available after partial failure, cancellation or a failed manifest write, so retry/restart reads only unfinished source revisions. The same explicit verification cutoff applies to capture and publication. Supplied stores remain caller-owned.

The caller retains this selection and its source revisions, provides stable source/destination identities, independent retained storage, exclusive writers and an owned manifest path. This operation accepts a fixed selection; it does not discover or durably close a live source listing, record pending intent before the first unknown digest, establish PostgreSQL recovery coverage or schedule recurring captures. An interrupted first checkpoint commit can leave unreferenced output; retry selects a fresh path when no checkpoint committed.

`ObjectBackupCaptureService.prepareInventory` persists that selection and its destination identity, payload prefix and manifest path as an immutable `ObjectBackupCapturePlan`. It canonicalizes entry order and participates in the caller's database transaction. Repeating matching intent returns the original plan; changing its selection or output locations fails. Preparation opens no storage and creates no transfer checkpoints. `getPlan` and scope-filtered `listPlans` use the caller's database context and ordinary offset pagination. The registered migration creates an empty plan table and preserves historical progress without inventing selections.

The key-based `captureInventory` overload reloads saved intent in a short database context and delegates to the existing capture/publication flow. A restarted service can resume partial work or retry publication using the key and caller-owned stores. `verifiedSince` is chosen for each execution; it is not frozen in the plan. Plans record selected input, while the existing revision ledger records copy progress. Preparing a plan does not bind storage addresses, retain source versions, claim writer ownership, dispatch jobs or establish database recovery coverage. Those existing caller requirements still apply.

`ObjectBackupCaptureService.configureStorage` fixes a plan's logical source mapping to existing bound IDs. Its independent backup ID is the plan's destination identity. Configuration uses the caller's transaction, opens no stores and returns `ObjectBackupCaptureStorage`; `getStorage` reads that mapping. Matching or concurrent replay preserves the saved record, while changed mappings, missing bindings and source/backup aliasing fail atomically. The migration preserves existing plans without inventing storage mappings. Multiple logical stores may intentionally share one bound source namespace.

`captureConfigured` resolves the saved IDs from explicit configurations or selected `backup.objectStores` entries and delegates to capture/publication. The existing storage service checks every selected address before opening any store and closes all operation-scoped stores on success, failure or cancellation. Unrelated configurations are ignored. Credentials remain runtime-only and can rotate while the saved addresses stay fixed. Restart and retry preserve the plan, mapping and verified revision checkpoints. The integrity cutoff remains explicit for each execution. Call outside an active transaction and retain exclusive writer ownership, source provider versions, stable storage accounts and owned output namespaces; this does not dispatch or schedule capture or coordinate writer ownership with other runs.

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

Storage's `getObjectInfo(path)` provides lightweight length, timestamp and available provider identifiers without fetching asset bodies. `listObjects(prefix, offset, limit)` discovers literal object keys and listing metadata through existing offset pagination, without per-object requests. These capabilities support source capture and destination checks. Listing positions can shift while source writes continue, and metadata alone is not a digest or retained revision. Ordinary transfer/publication verification still reads and verifies payloads. Closed source capture and recurring baseline/integrity orchestration remain separate work.

Storage also provides `getInputStream(path, version)` for exact retained S3 versions and Google generations, with no current-content fallback. Filesystem storage cannot address retained provider revisions. Capture must establish provider support and retention, then associate each selected revision with its content/database reference. `ObjectBackupCaptureService` uses these reads to produce verified manifest entries. Existing run transfer still reads caller-pinned source keys; capture orchestration is not yet connected to run preparation.

A configuration failure before the attempt leaves the run pending. If that job exhausts its queue retries, correct configuration, then use `cancelPending`, `retry` and `dispatch` with each returned version to obtain a new queue ID. A crashed `RUNNING` writer retains its ownership and still requires stopped-writer recovery. Pipeline orchestration, Studio/CLI actions and recurring backup creation remain separate work.

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
