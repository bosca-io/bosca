# Backup and recovery

Recover Bosca in two steps: **restore PostgreSQL, then restore object storage**.
The destination database may be replaced. Source writes continue during backups.

Store the initial backup on the configured object-storage service. A **Backup completed**
event can trigger a separate Pipeline to copy it elsewhere. Backup creation
works without an external copy destination. For recovery after losing the original
storage, use the externally copied backup. Save deployment configuration and
encryption/signing keys separately; manifests record key names, never secret values.

## Back up

Keep the deployment's existing PostgreSQL backups. CloudNativePG recovery uses its
own base backups and WAL. Docker and Swarm deployments can retain a custom-format
`pg_dump` archive. The **Back Up PostgreSQL** Pipeline action also produces an
independent `database.dump` and `manifest.json` using the configured libpq service.
It records lengths, SHA-256, PostgreSQL/extensions and known encryption-key requirements.

For assets, create a Pipeline with **Back Up Object Storage** (`objectBackup.backup`):

1. Set a stable source identity, scope and independent backup storage ID.
2. Map logical stores to configured source IDs in **Source stores**:
   `{"content":{"storageKey":"source-content","prefix":""}}`.
3. Connect its manifest output to Output. Use the existing Pipeline schedules and
   run controls; choose the backup interval for the deployment.

An empty prefix includes the configured namespace. Each run saves a new manifest.
New, changed and unfinished objects are copied; unchanged retained objects reuse
verified payloads. Retries use saved selections and checkpoints. Full checksum
rechecks can be scheduled with **Checksum recheck interval (days)**.

Capture reads current objects and compares the observed length, entity tag,
modification time and available provider identity around reads. No provider
versioning is required. A source object that changes
before it is copied fails that selection; start a new capture to select its new state.
A failure remains visible in Pipeline history, and completed payloads survive retries.

Database and object backups have separate capture times. Record the database
backup and object manifest selected for each recovery. Object listing during active
writes is not an atomic database/object snapshot. Confirm important content through
the recovered application before opening the destination to traffic.

## Copy a completed backup

Managed PostgreSQL and object backups emit the standard **Backup completed** event
(`bosca.backup.BackupCompleted`). It appears in the Event Catalog and can trigger a
Pipeline. Its payload contains `key`, `type`, `storageKey` and `path`, without credentials
or the object inventory:

- `POSTGRES`: `path` is the directory containing `manifest.json` and `database.dump`.
- `OBJECTS`: `path` is the object manifest. Copy it and every payload it references,
  including files shared with earlier manifests. Preserve their relative keys.

The event is dispatched with the completion transaction after publication and storage
close. Failed or partial backups and object restores do not emit it. Replayed completed
requests can emit again, so consumers identify an artifact by type, storage ID and path
and reuse completed copy work. Filter the copy Pipeline to the initial backup storage ID
so copying a backup does not start another copy of that result.

Backup completion and the external copy have separate outcomes. The copy Pipeline
owns its destination, retries and run history; retrying that copy does not require a new
database dump. A backup bucket on the existing object-storage service is sufficient for
initial publication. Keep source content and retained backups in separate buckets so
source discovery cannot include its own backup output. CloudNativePG's backups continue
to use the operator's existing mechanisms; this event covers Bosca's managed operations.

## Configure the recovery instance

Configure independent retained storage and the new content destination in
`backup.objectStores`. Storage IDs are literal map keys. Credentials stay in runtime
configuration. If a retained backup was copied to a different location, configure a
new storage ID for that copy and select it with `--backup`. Existing IDs stay bound
to their original locations, including bindings restored with PostgreSQL. For example:

```yaml
backup:
  objectStores:
    retained:
      type: s3
      urlPrefix: ""
      bucket: "${BACKUP_BUCKET}"
      region: "${BACKUP_REGION}"
      endpoint: "${BACKUP_ENDPOINT}"
      accessKeyId: "${BACKUP_ACCESS_KEY_ID}"
      accessKeySecret: "${BACKUP_ACCESS_KEY_SECRET}"
    recovered-content:
      type: fs
      urlPrefix: ""
      basePath: "${RECOVERED_CONTENT_PATH}"
```

Backup stores support filesystem and S3-compatible storage through the existing
storage factory. Configure `content.storage` to address the same destination used
for the logical `content` store. The API server and backup worker need matching
storage locations. Filesystem deployments must place the worker with the volume;
no shared ReadWriteMany volume is assumed.

## 1. Restore PostgreSQL

Keep destination application workloads stopped while replacing the database. The
source instance can keep running.

For Kubernetes, use the deployment's existing
[CloudNativePG recovery procedure](https://cloudnative-pg.io/docs/1.29/recovery/)
to bootstrap the replacement database. Use the guide for the installed operator
version and its configured backup method. Physical backups require a compatible
PostgreSQL major version and machine architecture. Use
[`pg_dump`](https://www.postgresql.org/docs/17/backup-dump.html) when moving between
architectures.

For Docker or Swarm, retrieve the selected dump using the backup provider's client.
A native Pipeline receipt supplies its configured storage ID and artifact prefix:

```bash
bosca --profile source backup postgres list --source primary --storage retained
bosca --profile source backup postgres get --key nightly-1 --json
```

Save the receipt's artifact path outside the source so recovery does not require
these queries. For S3-compatible retained storage, an example download is:

```bash
aws --endpoint-url "$BACKUP_ENDPOINT" s3 cp \
  "s3://$BACKUP_BUCKET/$DATABASE_ARTIFACT_PATH/" ./database-backup/ --recursive
```

Check the downloaded dump's length and SHA-256 against `manifest.json`, and inspect
its archive with `pg_restore --list`. For an existing plain SQL dump, use `psql`
instead. Provision a compatible PostgreSQL server with the required extensions and
restore role. Replace the destination database with an empty database owned by
that role, then restore the custom-format archive:

```bash
pg_restore --dbname="$RECOVERY_DATABASE_URL" --single-transaction --exit-on-error \
  --no-owner --no-acl --no-tablespaces ./database-backup/database.dump
```

This restores database objects and Bosca's application data, including its stored
permissions. Database roles/ownership and infrastructure settings belong to the
destination deployment. Restore the original keys required by encrypted data.
See the PostgreSQL [pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html)
and [pg_restore](https://www.postgresql.org/docs/current/app-pgrestore.html) references.

If using Bosca's standalone PostgreSQL restore service or Pipeline action, configure
`backup.postgres.restore.service`, `database` and optional `serviceFile`, `pgRestore`
and `psql` executable paths. It requires an empty, operator-owned destination and
uses a separate libpq connection. It does not replace a running application's database.

## 2. Restore object storage

After database recovery, start the destination API and backup worker against the
recovered database while keeping normal traffic and unrelated background work
stopped. Set `runners.enabled` to `[backupQueueRunner]` and `scheduler.enabled` to
`false` for this recovery deployment. Use a separate queue service for recovery.
Authenticate to the destination; source credentials are unnecessary.
Prepare a restore from the independently retained object manifest:

```bash
bosca --profile recovery backup objects prepare-restore \
  --request recovery-1 --destination recovery --backup retained \
  --manifest manifests/inventory.json --store content=recovered-content
```

Map every logical store in the manifest with another `--store name=storage-ID`.
Preparation saves the selection and returns a run key and version. Dispatch that
exact returned version, then inspect progress:

```bash
bosca --profile recovery backup objects dispatch --key "$RUN_KEY" --expected-version "$RUN_VERSION"
bosca --profile recovery backup objects get --key "$RUN_KEY" --json
bosca --profile recovery backup objects items --key "$RUN_KEY" --offset 0 --limit 20
```

Matching target files are reused without downloading their retained bodies again.
Missing and differing selected files are restored at their original keys. Files
outside the selected manifest remain untouched. Required payloads are checked
before asset writes; each copy verifies its final length and checksum.

After a failed or partial run, correct the cause, use `retry` with its observed
version, then dispatch the returned pending version. Completed files are reused.
A redelivered Pipeline job resumes its own checkpoints after interruption. Do not
recover a recorded active writer until its process has stopped.

Studio's **System → Backups** page shows native artifacts, object-run status,
current checkpoints and the last successful result. It can prepare, dispatch,
retry and cancel pending object restores. Manual and recurring captures use the
existing Pipeline editor, schedules and run history.

## Refresh and verify

A later recovery can replace PostgreSQL again, then restore a newer object manifest
with a new request key. Keep the same destination identity for the same storage
location. Replacing the database may discard prior restore progress; actual matching
target bytes still avoid another copy. Finish database replacement before preparing
new object work, and keep the progress database stable for that restore attempt.

Before serving traffic, check representative images, supplementary files, collections,
documents, relationships and permissions. Verify
that encrypted configuration can be read with the restored keys. Record both backup
identities, their times, the object run outcome and any missing content. A completed
object run reports only the objects selected by its manifest.

The integration suite exercises real pg_dump/pg_restore, database replacement,
source removal, application reads and incremental object recovery against isolated
PostgreSQL, NATS, filesystem and S3Proxy stores.

## Development

Run from the workspace root:

```bash
./gradlew :backup:core-backup:test :backup:backup:test
```

The S3Proxy recovery test starts isolated PostgreSQL and S3Proxy containers and
removes them afterward. It verifies an unchanged capture transfers no asset bodies,
copies the retained files to a second S3Proxy, then restores PostgreSQL and objects
after the original storage stops. A later recovery
replaces PostgreSQL again and copies only the changed file.

```bash
./gradlew :backup:backup:test --tests 'bosca.backup.PostgresBackupIntegrationTest.S3Proxy*'
```

`core-backup` owns contracts/models. `backup` owns repositories, workers, GraphQL
and Pipeline actions. The existing ZIP export/import remains a separate archive
feature; it is not the native database recovery path described here.
