# Bosca Atlas server

Bosca Atlas composes Git hosting, CI, artifact services, WorkOps and background execution. By default one
process serves the backend on port 8094 and the BML UI on port 8095, and consumes the Git,
pipelines, common, index, backup, profile, workops, content and messages job queues. Git build agents remain separate: Atlas creates and coordinates
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

Package the backend with `./gradlew :server:atlas-server:nativeDistZip`. The resulting
`server/atlas-server/build/distributions/atlas-server-native.zip` contains the executable and
every generated native runtime library. Unpack the complete archive into the Docker build context's
`artifacts/${ARTIFACT_SHA}/atlas-server-native/` directory. ImageIO uses generated JNI libraries;
copying only the executable leaves image metadata jobs unable to load AWT. The backend native
configuration retains headless operation and traced ImageIO reachability metadata.

Tune native compiler resources with `-Pbosca.native.buildHeap=32g` and
`-Pbosca.native.parallelism=4` when the automatic memory/thread budget is insufficient.
Both Atlas hosts accept these optional flags without changing optimization level.

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
| `ATLAS_RUNNER_QUEUES` | `git,pipelines,common,index,backup,profile,workops,content,messages` | Queues consumed by this process |
| `ATLAS_RUNNER_CONCURRENCY` | `16` | Consumer concurrency per selected queue |
| `ATLAS_RUNNER_LISTENERS_ENABLED` | `true` | CI, pipeline synchronization, WorkOps and communication listeners |
| `ATLAS_SYNC_LISTENERS_ENABLED` | `true` | Pipeline Git synchronization within the worker listener role |
| `ATLAS_SCHEDULER_ENABLED` | `true` | Schedule evaluation when the runner role is enabled |

Flags select HTTP surfaces and execution loops. Supporting services, schemas, migrations, and job
enqueuers stay available for cross-domain operations. Disabling workers retains job enqueueing and
history recording, without starting consumers, expiration recovery, worker listeners, or scheduling.
The supporting queue providers are registered so child completion can reach a parent on another queue.
Expiration recovery runs only for queues selected by this process.

Atlas serves one composed `/graphql` schema for its imported applications. The UI uses
`BML_GRAPHQL_ENDPOINT` for that shared Atlas API. The existing Git UI proxies use the same upstream
for authentication, subscriptions, raw files, and CI downloads. Independently scaled backend services
must remain reachable through that shared backend origin; the UI does not select domain endpoints.

WorkOps is imported from `:workops:workops-ui` into the same embedded and standalone hosts. Atlas
composes the owning WorkOps, Content, Forms, Comments, Calendar, Language and Scripting APIs and providers,
plus the Analytics counter, error-group and live-session contracts used by release health.
WorkOps routes cover activity, specifications, requirements, planning, boards, sprints, delivery and
settings. The [migration inventory](../../workops/workops-ui/docs/studio-ui-inventory.md) records source
parity, measured workflow evidence and remaining qualification gates. WorkOps, content and message
job enqueuers remain available with workers disabled.
Worker listeners close with their owning application.

WorkOps release controls use the existing Helm and Helm-values deploy adapters. Configure
`KUBERNETES_CONTROLLER_URL` for the separate [Kubernetes controller](../../kubernetes/README.md),
which owns cluster connections and Helm operations. API and worker processes use the same controller
configuration; deployment actions retain the initiating principal through that HTTP boundary.

Configured task forms use the existing Scripting `/api/v1/s/{key}` GET/POST routes for API-script
choices. WorkOps reloads the stored field configuration and forwards the resolved caller token
from its BML contract. Atlas uses the established remote scripting execution service; an existing
JVM scripting engine worker executes those scripts. Atlas's selected consumer queues are unchanged.

Content collaboration HTTP requests use the UI's same-origin
`/api/v1/content/metadata/{id}/document/collaboration` proxy. Release live sessions use the existing
GraphQL subscription relay at `/_git/subscriptions`, with the same caller authentication.

Optional AI credentials use the existing `koog` configuration and executor module. The direct
Kit capabilities resolve an executor when invoked, so task/document APIs can start without an LLM
credential. AI requests still require a configured provider. Content image jobs use
`content.images.resizer` and `IMAGE_RESIZER_URL`, following the main server's image-size defaults.

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

Set `STORAGE_URL_PREFIX` and `STORAGE_URL_UPLOAD_PREFIX` to the content API origin used by the
deployment. Workers must be able to reach signed download URLs there; their health-only listener
does not serve Content downloads. Keep the configured storage URL signer secret shared across roles.

The opt-in isolated Studio routing rehearsal retains Studio's platform API alongside Atlas's API.
It builds Studio with its locked workspace dependencies, stages the complete Node output, and uses
real Caddy configuration reloads for Studio → Atlas → restored Studio. Set
`BOSCA_SERVER_TEST_BINARY`, `ATLAS_SERVER_TEST_BINARY` and `ATLAS_UI_TEST_BINARY` to staged native
executables or executable launchers that reference complete staged JVM distributions by absolute
path. The fixture copies each executable; a copied JVM launcher must still locate its distribution.
Run with Node, pnpm and Docker available:

```bash
pnpm --dir web --filter @bosca/studio... install --frozen-lockfile
./gradlew :server:atlas-server:test \
  --tests 'bosca.atlas.server.AtlasWorkOpsCutoverIntegrationTest' -Patlas.workops.cutover=true
```

Reports under `build/reports/workops-cutover` include configuration, manifests, browser captures and
API readback. The measured routing/document case passes, including task indexing and in-app inbox
completion. The message-pipeline provider/configuration correction passes JVM, staged and native
SMTP cases in both worker layouts. The current native routing/document case passes in 117.915s;
the matching staged case passes in 101.081s. These isolated results do not authorize a deployment.

Atlas composes the existing device-service implementation for the shared message service without
adding the device GraphQL namespace. Its packaged configuration uses `MAILER_TYPE`,
`MAILER_FROM_NAME`, `MAILER_FROM_EMAIL`, `MAILER_UNSUBSCRIBE_URL` and `MAILER_PREFERENCES_URL`,
matching the platform server. Preserve that section in custom Atlas configurations used by workers.
The Compose template retains its existing platform mailer defaults. The notification delivery
integration test runs the installed WorkOps pipeline through real PostgreSQL/NATS and SMTP in both
worker layouts, with an HTTP contract fixture for the unchanged BML renderer. Without
`BOSCA_SERVER_TEST_BINARY`, tests launch the actual compiled platform module; set the variable to
an absolute launcher when qualifying a staged platform distribution.

The broad WorkOps workflow fixtures assert in-app delivery without installing the email pipeline;
the dedicated SMTP cases qualify email with normal platform installers. The fixtures do not run the
analytics ingestion sidecar or qualify every platform pipeline. Actual Linux ARM64 native media
cases now pass both production-image layouts: PNG/JPEG/GIF dimensions persist as `32x24`, SVG jobs
complete without an unsupported size, every matching image job completes, and original bytes remain
exact. Split and combined cases pass in 37.682s and 18.176s with zero failures, errors or skips.
The optimized backend and UI images retain all generated runtime libraries and traced ImageIO
metadata. The corresponding JVM cases also pass. External rendition processing uses the supported
empty-size fixture configuration; external image-processor execution is outside this scope.
Local GraalVM 25.0.3 on macOS excludes AWT native support. The current supported native media
qualification is Linux ARM64; other native workflow and operational gates are recorded separately.

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
WorkOps. The default packages install initial security, the Git and platform search indexes, search
transform settings, GitHub sync pipelines, and the shared default artifact sync/publication pipelines. Domain migrations register Git maintenance and CI requirement schedules.

`:server:atlas-ui-server` uses the same shared Atlas auth/landing and Git application as the
embedded listener. The API owns process-wide services. UI requests drain before backend shutdown;
a listener bind or installation failure stops the host. Worker consumers, scheduler loops, and
broadcast subscriptions are stopped with the application.

## Validation

```bash
./gradlew :server:atlas-server:test
./gradlew :artifacts:artifacts-git:test
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

Git and the artifact registry remain independent domain modules. Atlas selects the `artifacts-git`
CI registry adapter and its generated providers; standalone Git does not load registry modules.
Atlas also supplies the subsystem catalog to the imported Git UI. Shared authentication lives in
`bml-auth-ui`, while each host owns its login branding and landing routes.

Native process acceptance uses isolated PostgreSQL, NATS, search and file storage, and copies both
executables into fresh fixture directories. It exercises the combined host, a separate worker,
split registry/UI hosts, authentication, CI artifact completion, System routes/actions and the
artifact browser workflows. HubSpot and Bible provider transport tests use JVM fixture entry
points with intercepted upstream clients and run separately; their System pages are included in
the native route checks:

```bash
./gradlew --no-configuration-cache --no-parallel \
  :server:atlas-server:nativeCompile --build-args=-Ob \
  :server:atlas-ui-server:nativeCompile --build-args=-Ob \
  :server:atlas-server:nativeHostTest
```

`-Ob` selects a quick native validation build; omit it for release optimization. The acceptance task
always executes and retains process logs/configuration under `build/reports/native-host/`.

Use the explicit suite selector below with
`--tests 'bosca.atlas.server.AtlasWorkOpsIntegrationTest' -Patlas.workops.browser=true`
and `nativeHostTest` to exercise the implemented WorkOps workflows, including large
document transfers and collaboration between separate UI processes. The WorkOps inventory records
the compared routes, fixtures and acceptance scope.
`-Patlas.workops.browser.scripts=task-detail,requirements` selects named WorkOps browser suites in
either JVM or native acceptance. Release fixtures that replace external analytics/model execution
run through their separate JVM entry point; native results must state the selected workflow scope.

The bounded quick WorkOps qualification command is:

```bash
./gradlew --no-configuration-cache --no-parallel \
  :server:atlas-server:nativeCompile --build-args=-Ob --build-args=-J-XX:NewRatio=9 \
  :server:atlas-ui-server:nativeCompile --build-args=-Ob --build-args=-J-XX:NewRatio=9 \
  :server:atlas-server:nativeHostTest --tests 'bosca.atlas.server.AtlasWorkOpsIntegrationTest' \
  -Patlas.workops.browser=true -Patlas.workops.browser.scripts=task-detail,requirements \
  -Pbosca.native.buildHeap=32g \
  -Pbosca.native.parallelism=4 --max-workers=1
```

The generation ratio gives the compiler more space for long-lived objects within the same heap cap.
Quick runtime qualification does not establish successful release optimization.

System administration pages are imported from `:admin-support:system-ui` and are available
in both embedded and standalone hosts. Routes cover PostgreSQL, NATS and Meilisearch consoles, groups, scheduled jobs,
job history, package installation, configuration, storage/search settings, backups and utilities through the
shared Atlas API. The index worker consumes the existing initializer and reindex jobs.
Atlas uses the generated AI, Chat and Collaboration module registrars for the model catalog and
federation services. Its indexing configuration supplies the content search transformations. Metadata
services use the owning Bible provider and Mux implementations. Configuration providers supply settings,
and Forms providers support the Profiles form-schema installer. Federation administration is available
under `/system/integrations/federation`. See
[System UI](../../admin-support/system-ui/README.md) for the implemented scope and checks.

HubSpot settings and profile-context preview are available under `/system/integrations/hubspot`.
Atlas installs the existing HubSpot attribute types and sync pipelines. Bulk synchronization uses
the `profile` worker queue. The optional HubSpot client is selected at process startup; after a
token change, restart API and profile-worker processes to load the new credential.

The current release-optimized WorkOps qualification uses the following explicit scope. The four
release suites requiring JVM interception are excluded; their separate JVM checks remain required.

```bash
workops_native_suites=task-create,task-detail,task-labels,requirements,specifications,specification-detail,specification-delivery
workops_native_suites+=,planning,milestones,projects,boards,sprints,versions,environments
workops_native_suites+=,global-catalogs,automation,workflows,sla,components,permissions,notifications,approvals
workops_native_suites+=,task-descriptions,task-history,release-build,release-ci,release-environments,task-search,filters,inbox
./gradlew --no-configuration-cache --no-parallel --no-build-cache \
  :server:atlas-server:nativeCompile --build-args=-O3 --build-args=-J-XX:NewRatio=9 \
  :server:atlas-ui-server:nativeCompile --build-args=-O3 --build-args=-J-XX:NewRatio=9 \
  :server:atlas-server:nativeHostTest --tests 'bosca.atlas.server.AtlasWorkOpsIntegrationTest' \
  --tests 'bosca.atlas.server.AtlasNotificationDeliveryIntegrationTest' \
  --tests 'bosca.atlas.server.AtlasWorkOpsMediaIntegrationTest' \
  --tests 'bosca.atlas.server.AtlasWorkOpsCutoverIntegrationTest' -Patlas.workops.cutover=true \
  -Patlas.workops.browser=true -Patlas.workops.browser.scripts="$workops_native_suites" \
  -Pbosca.native.buildHeap=48g -Pbosca.native.parallelism=4 \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1
```

Before the ImageIO packaging correction, the macOS ARM64 backend builds at optimization level 3 in
28m50s and reuses the unchanged UI image. Five native cases pass with zero failures, errors or skips: both 30-suite workflow
layouts (550.525s/548.941s), both SMTP layouts (38.750s/37.235s), and the routing/document rehearsal
(117.915s). Both image hashes remain unchanged after runtime checks. Those five cases exclude the
new image-job assertion. The corrected Linux ARM64 backend builds at optimization level 3 in
41m08s with peak RSS 51.40 GB; the UI builds in 10m42s with peak RSS 13.93 GB. Their complete native
archives retain executable permissions and all generated libraries. Both actual native media cases
pass as described above. Five additional Linux native cases pass with zero failures, errors or
skips: both 30-suite workflow/collaboration layouts (597.048s/606.012s), both installed-pipeline SMTP
layouts (29.662s/34.322s), and real Studio routing/document rollback (110.615s). Together with the
two media cases, seven native acceptance cases pass against the same verified production images.
Core's coverage follow-up passes 1,991 tests, two transport soaks and the existing 95% line / 90%
branch gates, measuring 95.9474% lines and 90.0770% branches without changing runtime code or coverage
filters. The follow-up review passes all 34 browser workflow suites in both Atlas layouts and the
concurrent work-log deletion cases. The UI Kotlin aggregate measures 98.8037% lines and
90.0675% branches, including model serialization, and passes its new 90% branch gate.
Dependency findings remain documented in the inventory. Deployment and Studio retirement are
separate future work. The refreshed JVM run
passes seven cases, including image-job completion,
SMTP, routing rollback and the affected document workflow.

The broad workflow fixtures exercise in-app notifications with their email pipeline uninstalled;
dedicated SMTP cases install and verify that pipeline. Analytics ingestion is outside these
fixtures. Retained logs include eight distributed-lock release timeouts after shutdown begins;
all process termination assertions pass. These results do not claim clean logs or complete
platform background-job qualification.
