# Bosca Atlas server

Bosca Atlas composes Git hosting, CI, artifact services, and background execution. By default one
process serves the backend on port 8094 and the BML UI on port 8095, and consumes the Git,
pipelines, and common job queues. Git build agents remain separate: Atlas creates and coordinates
CI jobs, while agents claim jobs, execute build steps, upload artifacts, and report results.

Run from the workspace root with the existing PostgreSQL, NATS or Redis, object storage, and
Meilisearch services configured:

```bash
./gradlew :server:atlas-server:run
./gradlew :server:atlas-server:installDist
./gradlew :server:atlas-ui-server:installDist
```

`server/atlas-server/build/install/atlas-server/bin/atlas-server` is the installed JVM launcher.
Both hosts accept `-config=/path/to/config.yaml`. The backend's packaged configuration is
`atlas-server.yaml`; the standalone UI uses `atlas-ui-server.yaml`. JVM modules use Java 25.
Native build tasks are `:server:atlas-server:nativeCompile` and
`:server:atlas-ui-server:nativeCompile`.

## Process responsibilities

| Setting | Default | Responsibility |
| --- | --- | --- |
| `ATLAS_HTTP_PORT` | `8094` | Backend listener, including health endpoints in worker-only mode |
| `ATLAS_API_ENABLED` | `true` | GraphQL, subscriptions, OAuth, and authentication entry points |
| `ATLAS_GIT_ENABLED` | `true` | Git smart HTTP, LFS, webhooks, raw files, and CI-agent transports |
| `ATLAS_ARTIFACTS_ENABLED` | `true` | Docker/OCI, Helm, Maven, npm, raw, and ML registry protocols |
| `ATLAS_UI_ENABLED` | `true` | Embedded BML listener |
| `ATLAS_UI_HTTP_PORT` | `8095` | Embedded or standalone UI listener |
| `ATLAS_RUNNER_ENABLED` | `true` | Durable job consumers, worker listeners, and scheduling |
| `ATLAS_RUNNER_QUEUES` | `git,pipelines,common` | Queues consumed by this process |
| `ATLAS_RUNNER_CONCURRENCY` | `16` | Consumer concurrency per selected queue |
| `ATLAS_RUNNER_LISTENERS_ENABLED` | `true` | CI requirement/Kubernetes dispatch listeners and pipeline Git synchronization |
| `ATLAS_SYNC_LISTENERS_ENABLED` | `true` | Pipeline Git synchronization within the worker listener role |
| `ATLAS_SCHEDULER_ENABLED` | `true` | Schedule evaluation when the runner role is enabled |

Flags select HTTP surfaces and execution loops. Supporting services, schemas, migrations, and job
enqueuers stay available for cross-domain operations. Disabling workers retains job enqueueing and
history recording, without starting consumers, expiration recovery, worker listeners, or scheduling.
All three queue providers are registered so child completion can reach a parent on another queue.
Expiration recovery runs only for queues selected by this process.

Atlas imports Git UI and serves one composed `/graphql` schema for Git and artifacts. The UI uses
`BML_GRAPHQL_ENDPOINT` for that shared Atlas API. The existing Git UI proxies use the same upstream
for authentication, subscriptions, raw files, and CI downloads. Independently scaled backend services
must remain reachable through that shared backend origin; the UI does not select domain endpoints.

## Separate workers and UI

Keep UI and workers out of an API process:

```bash
ATLAS_UI_ENABLED=false ATLAS_RUNNER_ENABLED=false ./gradlew :server:atlas-server:run
```

Run a worker locally on a different health port:

```bash
ATLAS_HTTP_PORT=8096 ATLAS_API_ENABLED=false ATLAS_GIT_ENABLED=false \
  ATLAS_ARTIFACTS_ENABLED=false ATLAS_UI_ENABLED=false \
  ./gradlew :server:atlas-server:run
```

Run the standalone UI:

```bash
BML_GRAPHQL_ENDPOINT=http://localhost:8094/graphql \
  ./gradlew :server:atlas-ui-server:run
```

For separate transport pools, enable only the needed HTTP role, disable UI and workers, and route
Git clients or registry clients to that pool. Set `GIT_URL` to the public clone/transport origin and
`GIT_UI_URL` to the public UI origin. Configure `APP_URL`, `APP_ALLOWED_ORIGIN`, OAuth callbacks,
and allowed redirects for the public UI/API addresses. UI proxy endpoints are internal addresses;
browser GraphQL, OAuth, subscription, and file requests remain same-origin.

Artifact registry protocols may use the primary Atlas hostname or a separate registry hostname.
The current routes are `/v2/` for Docker/OCI, `/maven/`, `/npm/`, `/helm/`, `/raw/`, and `/ml/`.
Atlas also exposes the file-serving protocols under `/_artifacts/` for the UI proxy, using the
same registry handlers and authorization. UI-generated file URLs use this namespace so a Git raw
file and an artifact can have matching owner/namespace and repository names. A configured
`ATLAS_ARTIFACTS_URL` uses the registry's native paths on its public host. Native protocol paths
that overlap Git routes can use the separate registry listener described below.
For a separate registry process, enable artifacts and disable API, Git, UI, and workers:

```bash
ATLAS_HTTP_PORT=8097 ATLAS_API_ENABLED=false ATLAS_GIT_ENABLED=false \
  ATLAS_UI_ENABLED=false ATLAS_RUNNER_ENABLED=false \
  ./gradlew :server:atlas-server:run
```

Route the registry hostname to that listener and configure registry clients with that hostname
and the existing protocol paths. The primary Atlas API retains artifact administration under its
composed `/graphql` schema, even with `ATLAS_ARTIFACTS_ENABLED=false` there. Both processes share
the same authentication and storage configuration. This does not require separate UI domain endpoints.
CI agents already support a separate registry through `BOSCA_REGISTRY_URL` or their `registryUrl`
configuration field; point that at the registry origin for artifact setup and upload steps.

Every backend/worker process in a deployment must share PostgreSQL, queue/cache/pubsub namespaces,
object storage, JWT configuration, and `PIPELINE_SECRET_KEY`. Scaled Git/object delivery uses the
existing PostgreSQL/S3 DFS. The test fixture's shared local filesystem is only for isolated tests.

Select queue pools through `ATLAS_RUNNER_QUEUES`. Keep pipeline Git-sync broadcast subscriptions
on one designated worker process; set `ATLAS_RUNNER_LISTENERS_ENABLED=false` on other workers,
or disable just Git-sync pulls with `ATLAS_SYNC_LISTENERS_ENABLED=false`. Scheduling uses the
existing scheduler's distributed coordination. Queue redelivery, initiating principals, and the
normal CI execution/permission checks are preserved.

## Composition

Atlas owns its root SDL, namespace accessors, schema/dispatcher registrars, and provider list.
Git and artifacts retain their existing domain implementations. Git CI owns the registry-backed
produced/required artifact verifier, so CI requirement gates and producer completion do not require
WorkOps. The default packages install initial security, the Git search index, GitHub sync
pipelines, and the shared default artifact sync/publication pipelines. Domain migrations register Git maintenance and CI requirement schedules.

`:server:atlas-ui-server` uses the same shared Atlas auth/landing and Git application as the
embedded listener. The API owns process-wide services. UI requests drain before backend shutdown;
a listener bind or installation failure stops the host. Worker consumers, scheduler loops, and
broadcast subscriptions are stopped with the application.

## Validation

```bash
./gradlew :server:atlas-server:test
./gradlew :git:git-ci:test --tests 'bosca.git.ci.service.RegistryProducedArtifactVerifierTest'
./gradlew :git:git-ui-server:test
```

The Atlas integration tests launch production processes with isolated PostgreSQL, NATS,
Meilisearch, and filesystem storage. They exercise combined hosting, an optional registry origin,
and separate backend/UI
and queue pools: authentication, private Git clone/push, CI attribution, artifact requirements,
producer completion, agent claims/results, raw downloads, live logs, scheduler history, and graceful
shutdown. Configuration/schema tests check role selection and the Git UI/auth operation contracts;
listener lifecycle tests check bind/installation failures and sibling shutdown.

Artifact administration is available under `/artifacts` in the BML host. The authenticated Atlas
landing page links both applications. Artifact file uploads and reads use the UI origin; a separate
registry pool can be selected with `BML_ARTIFACTS_ENDPOINT`, while its public file URL origin can be
set with `ATLAS_ARTIFACTS_URL`. The UI continues using the shared Atlas GraphQL endpoint.
