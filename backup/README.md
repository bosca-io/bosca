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

`ObjectBackupRestoreService` restores a verified object manifest from independent backup storage to configured logical stores, preserving original object keys. It checks all retained payloads and target conflicts before writing assets, reuses matching files, and resumes interrupted writes through the existing progress service. Callers provide isolated destination namespaces, exclusive writers, and a progress database outside any application database being replaced. Object restore does not restore a PostgreSQL database or establish database recovery-point coverage.

Its `inspect` method verifies the retained payloads and reports each destination object as missing, matching, repairable from an earlier attempt, or conflicting. Inspection does not write assets or create/update progress. Restore repeats the inspection when it runs.

`ObjectBackupRunService.prepareRestore` records a fixed inventory and its input manifest path. `executeRestore` compares the stored manifest with that intent, then restores the selected inventory and records its outcome. Backup and restore operations have separate run and checkpoint identities, filtered histories, and last-success queries. Restore runs reuse the existing partial-result, cancellation, retry and frozen-result behavior; progress storage remains separate from any application database being restored.

Execution rejects a run that is already running before storage access or attempt changes. Recovery requires establishing that the earlier writer stopped, then recording failure through `ObjectBackupRunService.fail` with the observed version before another execution. Different runs still require exclusive writer access to their destination namespaces.

Read-only GraphQL queries under `backups.objectRuns` provide operation-filtered `list` and `lastSuccessful` reads and `run(key)` lookup. Run fields include the fixed inventory, paginated `items`, live `summary` and frozen `result`. List, item and inventory-object pages default to 20 entries and are capped at 100. These queries use the existing backup administrator/group and API-token scope checks.

GraphQL mutations under `backups.objectRuns` accept typed portable inventories through `prepare` and `prepareRestore`. Preparation records immutable run intent and exact checkpoints atomically. Repeating the same intent returns the existing run, including any recorded outcome. Preparation requires backup administration access; it does not capture or pin source revisions, open storage, dispatch transfers, or establish a database recovery point.

Tracked backup preparation reserves each retained destination/path for one length and SHA-256 digest, and each manifest path for its owning run. Matching inventories can share payload bytes across sources and scopes. Conflicting payload claims and manifest/path collisions fail before transfer and roll back preparation. The migration backfills existing backup progress and runs and rejects conflicting historical claims without selecting one. Database references protect reservations while checkpoints or runs use them. Restore target paths keep their separate semantics; destination identities must consistently identify the same storage namespace.

`ObjectStorageServiceFactory` in core storage creates independently configured stores. `ContentConfiguration` uses the injected factory for the shared application store and closes it at application shutdown. `withStorage` closes operation-scoped stores when the callback ends, including failure or cancellation. Worker dispatch remains separate work.

`ObjectBackupStorageService` records an immutable configured location for each backup storage identity. It rejects changed locations and duplicate address identities; credentials, URL presentation and bucket creation settings are not persisted. Its `withStorage` captures and checks the location in a short database context, then opens that exact configuration through the factory. `withStorages` checks all selected locations atomically before opening any and closes the opened stores if later setup or work fails. Active caller transactions are rejected before binding or opening storage. Filesystem paths resolve existing symlinks. S3 bindings require an explicit region and endpoint, with `AWS_ENDPOINT` supported as the endpoint fallback; credentials are supplied when opening and can rotate. The migration preserves existing inventories without inventing addresses for historical records. These checks identify configured addresses, not changed mounts, storage accounts or arbitrary endpoint aliases.

`ObjectBackupRunService.configureStorage` saves the independent backup storage ID and an exact logical-store mapping before the first run attempt. Mappings reference bound storage IDs and cannot change on retry. Restore destination IDs keep each logical store at the same location across later inventories, preserving shared checkpoint identity. Logical stores may share a restore location when their object keys are disjoint. `executeConfigured` opens the required caller-supplied configurations, reuses the existing transfer and verification behavior, and closes them after completion, failure or cancellation. Credentials are not saved with the run. The caller still pins source revisions and owns exclusive destination writer access. Worker dispatch remains separate work.

`backup.objectStores` supplies application-owned configurations by storage ID. `ObjectBackupStorageService.configurations(keys)` reads only the selected entries; IDs are literal map keys, including dots. Missing or malformed selected entries fail when used, while unrelated entries are ignored. `bindConfigured(keys)` validates all selected entries and binds their locations atomically in the caller's database context. Neither method opens a store, and configuration lookup does not access the database. There is no implicit fallback to `content.storage`.

`ObjectBackupRunService.executeConfigured(key)` resolves the saved storage IDs from this application configuration and uses the same scoped execution path as the explicit-configuration overload. Completed or cancelled history returns without catalog access, and an active run remains untouched. Credentials can rotate across application restarts while the location binding stays fixed. The API server and eventual worker must receive matching locations for the selected IDs. Worker dispatch and writer ownership remain separate work.

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
