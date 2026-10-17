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

Read-only GraphQL queries under `backups.objectRuns` provide operation-filtered `list` and `lastSuccessful` reads and `run(key)` lookup. Run fields include the fixed inventory, paginated `items`, live `summary` and frozen `result`. List, item and inventory-object pages default to 20 entries and are capped at 100. These queries use the existing backup administrator/group and API-token scope checks.

GraphQL mutations under `backups.objectRuns` accept typed portable inventories through `prepare` and `prepareRestore`. Preparation records immutable run intent and exact checkpoints atomically. Repeating the same intent returns the existing run, including any recorded outcome. Preparation requires backup administration access; it does not capture or pin source revisions, open storage, dispatch transfers, or establish a database recovery point.

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
