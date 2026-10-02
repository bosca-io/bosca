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

This intake endpoint currently stores delivery history. It does not yet invoke synchronization workflows, change repository refs or pull requests, or start builds.

## Dependencies

- Bosca Framework libraries (auth, DI, navigation, storage, GraphQL)
- Bosca Core (annotations, models)
