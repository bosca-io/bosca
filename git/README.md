# bosca-git

Native git hosting platform with WorkOps integration, source ref management for scripts and queries, and a full GraphQL API. Implements the git smart HTTP protocol on top of a Distributed File System (DFS) backed by PostgreSQL and S3-compatible object storage, enabling multi-pod horizontal scaling.

## Modules

| Module | Description |
|---|---|
| `core-git` | Domain models and service interfaces (pure contracts) |
| `core-git-ci` | CI contracts |
| `git` | Full implementation — DFS, services, transport, GraphQL, repositories |
| `git-ci` | CI implementation |
| `git-jobs` | Background job workers (GC, purge, webhooks, maintenance) |
| `git-server` | Executable server — composes modules into a GraalVM native binary |

## Prerequisites

- **JDK 25** (resolved via Gradle toolchains)
- **Docker** and **Docker Compose** (for local Postgres, NATS, S3Proxy)
- **Git** and **Git LFS** on `PATH` for the end-to-end CLI tests. On macOS,
  install Git LFS with `brew install git-lfs`; verify it with `git lfs version`.

## Build

Run these commands from the **workspace root**. `git` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :git:test  # Test Git modules
./gradlew :git:git-server:run  # Run the Git server
./gradlew :git:git-server:nativeCompile  # Build a native image
```

## Local Infrastructure

From the workspace root:

```bash
cd git
docker compose up -d               # Start services
docker compose down                # Stop services
```

| Service | Port |
|---|---|
| PostgreSQL | 5433 |
| NATS | 4222 |
| S3Proxy | 8000 |

## Architecture

JGit operates against S3 object storage with PostgreSQL metadata instead of local filesystem, enabling stateless multi-pod deployments. GraphQL batch resolution uses Caffeine caching for permission evaluations and repository lookups. Webhooks, task key extraction, and source ref sync are event-driven via NATS pub/sub. Repositories support soft-delete with 30-day retention before purge jobs permanently remove them.

## Build execution permissions

Grant repository `EXECUTE` to the security groups whose members should run builds. Repository `EDIT` alone does not authorize manual starts, reruns, build-anyway actions, or build approvals. WorkOps release starts and promotions require both repository write and execution grants, alongside their existing program and environment permissions.

Accepted push/tag updates refresh the pipeline catalog from the current default branch independently of build execution permission. Trigger matching and job definitions use the triggering commit; historical snapshots do not overwrite live catalog metadata, schedules, or environments. Removed files are archived so pipeline IDs, run history, and trigger occurrences survive removal and reappearance. Automatic CI checks the initiating principal's current execution permission before creating runs. Missing, deleted, or unauthorized principals do not start builds. Scheduled CI retains its assigned execution identity and permission checks.

Apply migrations `V41__pipeline_trigger_occurrences.sql` and `V43__pipeline_catalog_archival.sql` through the registered Git migrations before running the updated CI worker. Each durable trigger job can create one run per pipeline; its reservation, jobs, and initial commit statuses commit together. A rolled-back attempt can retry, and redelivery skips completed creation. This does not deduplicate separately submitted events.

Deploy the updated GraphQL schema before the updated Studio: its build controls query `GitRepository.canExecute` and `WorkOpsReleaseRepositoryAccess.canExecute`.

The server compiles to a GraalVM native image for sub-100ms startup and low memory footprint, with a JVM fallback via Shadow JAR.

## GitHub pairing and delivery intake

Apply the registered Git migration `V44__github_intake.sql` before using intake. Administrators configure `savePair`, `mapUser`, and `unmapUser` under the `github` GraphQL mutation namespace. The `github` query namespace exposes `pair`, `users`, and `deliveries`. Repository pairing uses GitHub's immutable numeric repository ID; user mappings use immutable human user IDs and Bosca principal IDs. These mappings grant no permissions. Pairing and delivery history queries are administrator-only because accepted payloads can contain private repository content.

Credentials remain in the encrypted Pipeline secret store, managed through Pipelines → Secrets. A pair stores the webhook and token secret names. Pairing defaults to disabled; enabling it requires both secrets to exist. Configure the repository webhook to send JSON to `/api/webhooks/github/<Bosca repository UUID>` with the matching webhook secret.

Intake verifies `X-Hub-Signature-256` against the exact raw body bytes and checks the signed repository ID. `X-GitHub-Delivery` reserves one persisted occurrence, including across redelivery; changing the body, event, or repository under that identifier returns a conflict. Accepted push and paired-repository pull request deliveries retain the original user mapping. Unknown and bot users remain unattributed, and fork-origin pull requests and other event types are recorded as ignored.

Each eligible delivery dispatches a `GitHubDelivery` event through the standard event system. Configure an inbound pipeline with `triggered = true` and input type `bosca.git.model.GitHubDelivery`; matching uses the event type and does not require a particular pipeline key. The event carries the delivery ID, originating principal and original receipt time. Redelivery dispatches the stored occurrence again, preserving that data. Unknown and bot senders have no originating principal.

Event dispatch uses the existing pipeline jobs, execution identity, history, trigger configuration and admission limits. Synchronization nodes consume the delivery data and must preserve its originating principal when calling Git services so build authorization checks the initiating person. Those nodes must track completed synchronization operations so redelivery does not repeat them.

Apply `V45__github_ref_synchronization.sql` for branch/tag synchronization state. The GitHub package installs two event-triggered graphs: **GitHub: Import Refs** consumes verified push deliveries, and **GitHub: Export Refs** consumes ordinary `RefUpdateEvent` events. Their nodes use the existing durable pipeline backing jobs. Reinstalling preserves graphs already carrying those keys.

Transfers preserve the original commits and annotated tag objects. Inbound changes use the ordinary ref notifier with the delivery's persisted originating principal. Completed push deliveries are recorded, so redelivery does not import again or trigger additional builds. Echoes whose refs already agree are no-ops; stale occurrences cannot replace a newer source ref. Fast-forwards converge. Force updates and deletions require an unchanged common target, or the matching old value on initial synchronization. Independent edits, including update-versus-delete races, retain both sides and appear in the administrator-only `github.refStates` query.

Inbound ref changes, synchronization state and notification registration share a PostgreSQL transaction. An interrupted import rolls back the ref and can retry with its original principal. The repository write lock remains held through commit.

**GitHub: Reconcile Refs** runs hourly through the existing pipeline scheduler and calls the same ref operations to recover missed changes. Verified, unprocessed pushes are redispatched as ordinary events; reconciliation defers their refs to the import pipeline so their original attribution is retained. Each ref commits before releasing its write lock, allowing native pushes between transfers. A failed pair does not prevent the remaining pairs from reconciling; the run reports accumulated failures afterward. Administrators can also call `github.reconcileRefs(repositoryId: ...)`. Changes recovered without a verified delivery have no originating principal and therefore do not authorize builds. If a verified push arrives later and the imported ref write is still current, its stored principal triggers CI once without repeating ref notifications. A subsequent native write prevents this attribution, including when it restores the same SHA. Reconciliation preserves unresolved initial deletion conflicts and never independently merges divergent histories.

Pull request mirroring and remote artifact publication are still pending. The ref importer skips pull request deliveries; those remain in verified delivery history for the pull request workflow.

## Dependencies

- Bosca Framework libraries (auth, DI, navigation, storage, GraphQL)
- Bosca Core (annotations, models)
