# Bosca Helm charts

Public OCI chart releases use GitHub Container Registry:
`oci://ghcr.io/bosca-io/bosca/charts/<chart-name>`. Each release publishes the same
chart packages through both a conventional Helm repository and OCI.

## Install or upgrade from GitHub

Deploy infrastructure first, then application services, using your deployment's
values files:

```bash
: "${BOSCA_VERSION:?Set the published Git release tag to install}"
helm upgrade --install bosca-infra oci://ghcr.io/bosca-io/bosca/charts/bosca-infra --version "$BOSCA_VERSION" -f infra-values.yaml
helm upgrade --install bosca-services oci://ghcr.io/bosca-io/bosca/charts/bosca-services --version "$BOSCA_VERSION" -f services-values.yaml
```

For an existing deployment, use its current release names, namespaces and values
files. Chart versions follow the selected Git release tag. Update any version
constraints that still select the former independent `0.1.x` chart releases.

## Kit

Published `bosca-services` charts include Kit's image pins, native versions, and
workload defaults. Add this to the normal services values and upgrade the release:

```yaml
global:
  kit:
    enabled: true
    harness: codex
```

Codex uses `openai-token/token`; Claude uses `anthropic-token/token`. Select
`harness: claude` or override `global.kit.providerSecret.name` and `.key` to use
another existing Secret. The chart generates matching API/Runner profiles and
the controller's JobProfile. Managed defaults require no private policy seed.
Optional overrides include `global.kit.profile`, resources, state/workspace sizes,
node selectors, and global/per-user admission limits. Existing content object
storage holds the private archives.

Kit Jobs inherit `bosca-kubernetes-controller.imagePullSecrets`, like the
controller's other Pods; the default is `docker-registry`. Kit uses the existing
Kubernetes dispatch queue and does not override Runner's controller HTTP URL.
If you rename the API Service with `bosca-server.nameOverride` or
`fullnameOverride`, also set `global.kit.boscaEndpoint` to its GraphQL URL. The
umbrella chart rejects an implicit endpoint that would use the former name.

Kit runtimes have no direct internet access. With `global.kit.egress.enabled`
(the default), the umbrella chart adds a `<release>-kit-egress` Deployment and
Service (port 3128) running `bosca harness egress` from the `bosca-cli` image, and
adds `egressProxy` and the provider's `egressHosts` (`api.openai.com:443` or
`api.anthropic.com:443`) to the generated profile. It also adds two NetworkPolicies:

- `<release>-kit-runtime` lets Kit runtime Pods accept connections only from
  Runner Pods on 8080 and connect only to cluster DNS (`k8s-app: kube-dns`, port
  53), bosca-server Pods on 8080 and the egress proxy on 3128.
- `<release>-kit-egress` lets the proxy accept connections only from Kit runtime
  Pods and connect to cluster DNS, bosca-server Pods, the Pods behind the public
  Bosca routes, and public IPv4 addresses.

The proxy supports only HTTPS `CONNECT` with the runtime session's credential. It
permits every Gateway hostname on port 443, the profile's `egressHosts`, the
remote MCP registrations assigned to the session's agent, and the `egressHosts`
list in the private `kit.harness` policy. It refuses other destinations that
resolve to private, loopback, link-local, shared or other non-public addresses.
The policies take effect only with a network plugin that enforces NetworkPolicy.
The proxy image tag defaults to the CLI release of the bundled Kit runtime and
uses the controller's `imagePullSecrets`; `global.kit.egress` accepts `replicas`,
`resources`, `image`, `imagePullSecrets`, `nodeSelector` and `tolerations`.
Installing `bosca-kubernetes-controller` without the umbrella chart renders no
proxy or policies.

Maintainers publish Kit runtime images before packaging the matching charts.
Packaging reads the images' immutable identities and `io.bosca.kit.versions`
labels and embeds them in `bosca-common`; operators do not maintain version or
kubelet-identity copies. Source-chart/custom-image development can supply
`global.kit.runtime` metadata. `release.sh package --kit-images FILE` accepts a
validated pre-resolved catalog for offline packaging tests or an alternate build.

## Controller-managed TensorFlow Serving

TF Serving runs only through the Kubernetes controller. With `provisioning.enabled: false` (the default),
the standalone chart creates no resources. With it enabled, the chart creates a `ServingModelProfile`
and its ServiceAccount. The controller discovers exported generations and maintains separate
ServingModel resources, Pods and version-specific Services. The chart supplies resource, scheduling,
security, artifact and health-check settings through the profile's native pod template.

In `bosca-services`, use `tf-serving.provisioning.enabled` to enable this chart. This is the only switch;
the former `tf-serving.enabled` value no longer controls it. There is no shared TF Serving Deployment,
polling sidecar, Service or PVC. Keep the controller running to provision and reconcile model instances.

Publish matched controller, model-loader, server and runner images from this revision before enabling
provisioning. Earlier loader images do not support init downloads, and earlier controller images do not
reconcile ServingModels. Deployment-owned values must select the updated images.

When upgrading an existing release, the new chart removes its old TF Serving Deployment and shared
Service, including when provisioning is disabled. To move traffic before removing that deployment:

1. Install the new controller image and both ServingModel CRDs. When upgrading an existing Helm release,
   apply the new CRD files from `helm/bosca-kubernetes-controller/crds/` explicitly; Helm does not upgrade CRDs.
2. Install the profile as a separate `tf-serving` chart release with a distinct `fullnameOverride`,
   `provisioning.enabled: true`, and deployment-owned resources and credentials. Every version gets its own
   per-Pod storage; shared PVCs are unsupported. The profile defaults to one candidate and a 120-second drain.
   Configure `loader.secrets.boscaToken` with administrator or matching artifact push permissions for terminal
   serving claims and failure reports; the artifacts pull token remains read-only. `provisioning.failureGraceSeconds` defaults
   to 600 seconds after an observed candidate failure. Use the same distributed lock backend on controller
   and runner. Before queuing activation, the controller claims the selection revision on the ServingModel
   so delayed retirement deletes fail their Kubernetes resource-version precondition.
3. Wait for the current active generation's ServingModel and exact model status endpoints to become ready.
4. Configure `RECOMMENDATIONS_CONTEXT_MODEL_URL_TEMPLATE` on both server and runner to match the profile,
   for example `http://tf-serving-model-{version}.models.svc:8501` for namespace `models` and the default prefix.
   Roll out these applications after the active version's endpoint is available.
5. After all consumers have moved, upgrade the original release to remove its old TF Serving resources.
   If that release contains only TF Serving, it can instead be uninstalled. Keep the new profile release.
   Subsequent context activations validate their own candidate endpoint and switch the active version
   without restarting another version's Pod.

Plan capacity for the active models plus `provisioning.maxCandidates` additional Pods. Candidates wait if
this capacity is occupied by another candidate or a draining version. Failed discovery preserves loaded
instances. See the [controller lifecycle](../kubernetes/README.md#controller-managed-serving-models) for details.

Resource and scheduling overrides use the ordinary chart values. For `bosca-services`, put them under
`tf-serving`; for a standalone `tf-serving` release, omit that outer key:

```yaml
tf-serving:
  provisioning:
    enabled: true
    maxCandidates: 1
  resources:
    requests:
      memory: 6Gi
    limits:
      memory: 6Gi
  loader:
    resources:
      requests:
        memory: 128Mi
      limits:
        memory: 512Mi
```

These resources apply to each model Pod. `loader.resources` controls the download init container;
`nodeSelector`, `affinity`, `tolerations`, `livenessProbe`, and `readinessProbe` pass into the same native
pod template. A profile change affects newly created ServingModels. Existing instances retain their
captured settings, so a Helm resource change does not restart the active model. For an existing polling
installation, follow the separate-release migration above before upgrading its release.

Configure the version endpoint in both application charts through Helm values. This example uses
namespace `bosca` and the default `tf-serving-model-` Service prefix:

```yaml
bosca-server:
  appConfig:
    tfServing:
      contextModelUrlTemplate: "http://tf-serving-model-{version}.bosca.svc:8501"
bosca-runner:
  appConfig:
    tfServing:
      contextModelUrlTemplate: "http://tf-serving-model-{version}.bosca.svc:8501"
```

The application charts retain the `RECOMMENDATIONS_CONTEXT_MODEL_URL_TEMPLATE` and
`RECOMMENDATIONS_TF_SERVING_URL` environment defaults when these Helm fields are not overridden.
`appConfig.tfServing` also accepts the other serving settings, such as `url` and `timeoutSeconds`.

## Container memory and heap sizing

Set memory requests, memory limits and `maxRAMPercentage` in your deployment's
values file. The server defaults to 65 percent; Atlas, runner, Git, artifacts,
collector and Kubernetes controller default to 75 percent. The percentage sets
maximum heap size relative to the container memory limit; leave room for memory
used outside the heap.

For the services umbrella chart:

```yaml
bosca-server:
  maxRAMPercentage: 65
  resources:
    requests:
      memory: 1Gi
    limits:
      memory: 2Gi
bosca-atlas-server:
  maxRAMPercentage: 70
  resources:
    requests:
      memory: 1Gi
    limits:
      memory: 2Gi
bosca-runner:
  maxRAMPercentage: 75
  workers:
    default:
      resources:
        requests:
          memory: 2Gi
        limits:
          memory: 2Gi
```

Standalone chart values omit the service-name wrapper. Runner memory is set per
worker under `workers.<name>.resources`; its `maxRAMPercentage` applies to every
worker. Percentages accept integers or fractional values such as `62.5`.

## Design Library site

`bml-design-catalog` serves the interactive Design Library catalog as a GraalVM native executable
on port 9093. Enable the optional services dependency and supply a hostname in the deployment's
own values file:

```yaml
bml-design-catalog:
  enabled: true
  gateway:
    enabled: true
    hostnames: [design.example.com]
```

With the shared Gateway enabled, the services chart adds its TLS listener, HTTPS route and HTTP
redirect. The image and chart use the selected release tag, with normal registry/tag overrides.
The catalog needs no Bosca API, database or infrastructure credentials. Its examples keep state
in one process; the chart defaults to one replica and Recreate rollouts. Restarts reset demo state.
See the [catalog deployment guide](../bml/bml-design-catalog/README.md#deployment) for local image
builds and standalone Service deployment.

## Bible usage worker

The runner chart enables `fumsQueueRunner` for API.Bible usage reports. If your
deployment overrides `appConfig.runners`, include `fumsQueueRunner` in that list
(`bosca-runner.appConfig.runners` in umbrella values).

The collector chart enables the FUMS transform by default. The collector and runner
charts expose `appConfig.fumsTransformEnabled`; the runner defaults to `false`.
Enable it on the runner only when it handles FUMS event processing instead of the
collector. `FUMS_TRANSFORM_ENABLED` overrides this setting on those applications.

## Infrastructure backends and Dragonfly backups

The infrastructure chart pins Dragonfly to `v2.0.2`. Set `redis.image` in your
deployment's infrastructure values to override the full image reference:

```yaml
redis:
  image: docker.dragonflydb.io/dragonflydb/dragonfly:v2.0.2
```

The chart passes this image to the Dragonfly operator through `spec.image`.
Apply it with the existing infrastructure release name, namespace and values file.

The server, runner, collector, Atlas, artifacts server, Git server, and Kubernetes
controller charts expose backend selections under `appConfig`. For example:

```yaml
bosca-server:
  appConfig:
    natsEnabled: false
    jobQueueFactory: redis
    pubsubType: redis
    counterType: redis
    liveSessionsType: redis
    cacheType: redis
    distributedLockType: redis
    redisHost: redis
    redisPort: 6379
```

`redisHost` names a Service in the release namespace. A Redis connection is rendered
when any backend selects Redis. Distributed locks default to Redis; the other backend defaults remain NATS; an empty
`liveSessionsType` follows `pubsubType`. `natsEnabled` controls the NATS connection,
monitoring configuration and token environment variable. Domain implementations
that directly require NATS still need a NATS connection; these options only select
the configurable infrastructure backends.

Server and runner charts expose `appConfig.analyticsProcessorEnabled` (defaults
false and true, respectively). The collector's `appConfig.eventRepositoryType`
selects `nats` or direct `iceberg` storage. `appConfig.iceberg.s3` configures the
warehouse bucket separately from content storage, and
`secrets.icebergS3.secretName` supplies existing warehouse object-storage
credentials. Without that Secret, the collector retains its content-storage
credential environment variables.

The infrastructure chart exposes Dragonfly's `redis.snapshot`, `redis.extraArgs`
and `redis.env`. Native snapshots may use a persistent volume or an S3 URI. For
S3 RDB snapshots, set `snapshot.dir`, `snapshot.cron`, `--dbfilename=dump` and
`--df_snapshot_format=false`, plus endpoint flags and credential Secret references
in `env`. Dragonfly loads its latest native snapshot on startup.

`redis.backup.enabled` adds a CronJob that archives the RDB object named by
`redis.backup.bucket` and `snapshotKey`. It copies to `archivePrefix` hourly by
default and deletes archive objects older than `retentionDays` (default seven).
The job uses `redis.env` for existing credentials and `redis.backup.endpoint` for
the object-store endpoint. Its retention only covers the archive prefix; the
latest restore snapshot remains separate. All backup resources are rendered by
the chart, without a post-renderer.

NATS distributed locking has been removed. Set `appConfig.distributedLockType: redis` in every
application chart and configure the same Redis endpoint/database for services sharing locks.
Remove any `DISTRIBUTED_LOCK_TYPE=nats` environment overrides. PostgreSQL locking is not implemented.
When upgrading a deployment that used NATS locks, stop all consumers of the shared locks before
starting them with the new images and configuration. NATS and Redis
locks do not coordinate with each other during a rolling transition.

## Pipeline encryption key

The server, runner and Git server inherit `PIPELINE_SECRET_KEY` from their shared
environment ConfigMaps (`server-v3-env` by default). The Git server does not
override this with a separate Kubernetes Secret. When customizing
`envFromConfigMaps`, supply the same pipeline key to all three applications.

Before upgrading an existing deployment, preserve the effective key used by the
server and runner to encrypt its stored pipeline secrets. The key must be a
Base64-encoded AES key (16, 24 or 32 bytes after decoding). The old Git server
`secrets.pipeline` values are no longer used. If the server and runner currently
use the application's development fallback, removing the Git override makes it
use that same fallback; moving stored secrets to a different key requires an
explicit re-encryption migration. Restart all affected pods after changing a
shared environment ConfigMap.

## Optional Git backend

`bosca-git-server` serves only the Git API and transports on 8091. Its image,
Service and probes have no UI listener. Use an image built from this revision.
Set `appConfig.gitUrl` for canonical transport URLs and `appConfig.uiUrl` for the
external UI origin (default `https://atlas.<domain>`). Its Git hostname route
redirects known UI pages/assets to that external origin and sends API/transport
requests to 8091. Authentication return origins follow the UI URL; provider
callbacks remain on the configured Git API URL.

Git shares the existing database, storage, JWT and pipeline key. It has no runtime
Atlas, Git UI or artifact registry dependencies. Registered migrations still run
at startup. The standalone artifacts chart remains available on 8084.

## Bosca Atlas

`bosca-atlas-server` serves Git, artifact protocols and the composed `/graphql` API on
8094, with the optional BML UI on 8095. Enable it explicitly in deployment-owned
values. This combined example replaces the separate Git and registry pods:

```yaml
global:
  domain: example.com
bosca-git-server:
  enabled: false
bosca-artifacts-server:
  enabled: false
bosca-atlas-server:
  enabled: true
  gateway:
    hostnames: [atlas.example.com]
    gitHostnames: [git.example.com]
    registryHostnames: [artifacts.example.com]
  appConfig:
    databaseUrl: jdbc:postgresql://postgres:5432/bosca
    artifactsUrl: https://artifacts.example.com
```

Supply the existing database/storage/auth environment and initialization Secret,
and pre-create the Atlas
PostgreSQL credential Secret (`pg-creds-bosca-atlas-server` by default), as with the
other server charts. Atlas must use the same database, storage, JWT, realm and
`PIPELINE_SECRET_KEY` as the main server and runner. Its `SECURITY_ENCRYPTION_KEY`
is read from `secrets.cfg.secretName` / `secrets.cfg.key` (defaults: `cfg` / `secret`)
and must also match the main server and runner. The services umbrella reuses the
existing Secret; standalone installs can set `secrets.cfg.create: false` to reuse
it or supply its value through `secrets.cfg.value` when creating it. The umbrella
defaults Atlas's embedded workers/listeners/scheduler off because the platform runner already owns
them. A standalone chart defaults to embedded workers; `runner.enabled`,
`runner.queues`, `runner.concurrency`, `runner.listeners` and `runner.scheduler`
select that responsibility.

Atlas rollouts always use `RollingUpdate` with `maxUnavailable: 0` and a positive
`maxSurge`, so a replacement must become ready before the last available pod stops.
Static replicas and HPA bounds have a floor of one. Its disruption budget is
enabled by default with `minAvailable: 1`; a larger integer minimum can be selected.
These protections also apply when older values contain `maxUnavailable: 1` or
`strategy.type: Recreate`. A single replica can still be interrupted by a process
or worker failure. Its disruption budget blocks voluntary eviction until another
replica is available; worker maintenance needs a second replica first.

The shared Gateway adds TLS listeners, certificates and HTTPS redirects for all
three configured hostname lists. Atlas UI pages/actions/assets use 8095; GraphQL,
Git smart HTTP/LFS/raw files, webhooks, CI files and registry protocols use 8094.
The Git alias preserves transport URLs and redirects Git UI pages to Atlas.
The registry alias rewrites raw/Maven/npm/Helm/ML file paths into Atlas's existing
`/_artifacts` namespace, avoiding Git raw-file ambiguity. Docker `/v2` paths stay
unchanged. Atlas's own file UI also uses `/_artifacts`.

To run dedicated backends, enable their existing charts and select their Service
names in Atlas. Git remains backend-only; all UI stays on Atlas:

```yaml
bosca-git-server:
  enabled: true
  gateway:
    hostnames: []
bosca-artifacts-server:
  enabled: true
  gateway:
    hostnames: []
bosca-atlas-server:
  backends:
    git:
      serviceName: bosca-bosca-git-server
      port: 8091
    artifacts:
      serviceName: bosca-bosca-artifacts-server
      port: 8084
```

Use the actual release's Service names. Atlas's routes own all configured public
hostnames. Git transports, raw files, CI files and webhooks go to the selected Git
backend; registry protocols go to the selected artifacts backend. Shared
`/graphql` and authentication stay on Atlas. Embedded UI file proxies use the
selected registry endpoint automatically. For a separate UI, also set that UI's
`appConfig.artifactsEndpoint` to the registry's private URL. Empty backend Service
names select Atlas itself; disable the dedicated charts when falling back. A
backend's own Gateway route is only needed when it owns a separate hostname; avoid
duplicate routes for the same host.

For a separate UI, enable `bosca-atlas-ui-server`, set the backend's `ui.enabled`
to false and `ui.serviceName` to that UI's Kubernetes Service name. `ui.port` is the
UI Service port (8095 by default). The UI chart's `appConfig.graphqlEndpoint`
selects the Atlas API; its default targets Atlas in the same release. Set
`appConfig.artifactsEndpoint` only when UI file requests use another registry,
and `appConfig.artifactsUrl` for its public protocol origin. The backend's Gateway
route still owns the public Atlas hostname. UI and API replicas/HPA scale independently.
The same backend chart can run workers or registry REST separately by selecting
`roles.api`, `roles.git`, `roles.artifacts`, `ui.enabled` and `runner.enabled`, with
`gateway.enabled: false` for workers. Embedded UI requires the API role.

Register `https://atlas.example.com/oauth2/google/callback` with the Google provider
and supply its client credentials through the existing environment sources. Custom
origins use `appConfig.atlasUrl` and `appConfig.gitUrl`. Use Atlas/Atlas UI images
built from the matching release; image publication is separate from chart rendering.

Routing verification:

```sh
python3 helm/tests/test_atlas_routes.py
helm unittest helm/bosca-atlas-server helm/bosca-atlas-ui-server helm/bosca-services
```

## Maintainer release process

`.bosca/pipelines/release-helm.yaml` publishes each chart to Bosca Artifacts' HTTP
Helm repository and OCI registry in namespace `bosca-charts`. Run it against an
existing Git tag. Packaging requires that tag's bare semantic version, such as
`6.31.0` or `6.31.0-rc.1`, and applies it to every chart and bundled local
dependency. The same version identifies the HTTP Helm package and OCI tag;
checked-in chart versions are only development defaults.

Publish that tag's Codex and Claude runtime images before running the chart
release. Packaging reads their deployment metadata once and fails if an image
is unavailable or does not match the release. If the release event starts the
chart job before the images are published, rerun the chart release after the
image jobs finish.

Bosca application `appVersion` defaults and explicit Bosca image tags (including
the model loader init container) also use the release tag. Bosca images are identified
by their default `image.registry: ghcr.io/bosca-io/bosca`. Publish the image
releases for that tag before deploying the charts. Deployment values can still
override image tags. Upstream images such as TensorFlow Serving and Text
Embeddings Inference keep their own versions. Tags with build metadata (`+...`)
are rejected because container image tags cannot contain `+`.

Both destinations use the registry URL and agent token supplied by
`setup-registry`. Traditional Helm packages and OCI-packaged charts share the
`bosca-charts` namespace. Artifacts stores them as separate Helm and OCI
repositories, distinguished by artifact type. Application container images use
the `bosca` namespace. No additional CI secrets are required.
External forwarding belongs to the Artifacts server, as with image releases.
Configure each chart's OCI artifact repository through Studio's registry sync
controls with GHCR destination `bosca-io/bosca/charts/<chart-name>`. Enable public
package visibility and verify the chart's tag on GHCR before announcing the
release. This release pipeline does not configure those destinations.

The publisher validates the checked-in dependency locks with Helm before stamping
chart metadata in a temporary copy. Stale locks fail the release. It pins local
dependencies to the release tag and third-party dependencies to their checked-in
locked versions. Helm rebuilds the staged dependency locks; their original timestamps
are retained so rebuilding the same release does not change chart contents.
The publisher lints and renders every application chart, then packages each chart once.
Dependencies are included in the packages; development `file://` references are
retained in their metadata.
Consumers install the complete packages without rebuilding dependencies. The
same archive goes to both destinations, and downloaded SHA-256 checksums are
verified after publication. Source charts, values and dependency locks remain
untouched. The old `prepare-for-publish.sh` helper is not used by this pipeline.

Before uploading, the publisher checks all versions at both destinations.
Existing versions with identical chart files are accepted; changed files fail the
release. Helm adds packaging timestamps to archives, so a rebuild can have a
different checksum without changing the chart. The publisher reuses the existing
archive bytes and updates the local packages and checksum manifest after all
versions pass inspection. If both destinations already contain a version, their
archive bytes must match.
The package directory contains the archives and a `release.tsv` checksum
manifest. A failure between destinations can leave a partial release: retain
that directory and rerun `publish` with those exact archives to finish it.
Changed charts need a new release tag rather than replacing an existing release.
Publication across the two registries is not atomic; keep other publishers from
writing the same versions while a release runs.

## Local packaging and publishing

Prerequisites: Bash, Helm 3 with OCI support, Python 3 with PyYAML, curl and standard shell utilities.
SHA-256 checks use `sha256sum` or `shasum`. The release pipeline
uses Helm 3.22.0. The Git routing regression test also requires Python 3 and
checks the rendered paths with RE2. From the workspace root:

```bash
python3 -m venv /tmp/bosca-helm-tests
/tmp/bosca-helm-tests/bin/pip install -r helm/tests/requirements.txt
export PATH="/tmp/bosca-helm-tests/bin:$PATH"
bash helm/scripts/release-test.sh
python3 helm/tests/test_release_versions.py
python3 helm/tests/test_git_routes.py
: "${BOSCA_VERSION:?Set the Git release tag being packaged}"
bash helm/scripts/release.sh package --version "$BOSCA_VERSION" --output /tmp/bosca-helm-packages

: "${BOSCA_REGISTRY_URL:?Set the maintainer publishing registry URL}"
export HELM_REPO_URL="${BOSCA_REGISTRY_URL%/}/helm/bosca-charts"
registry_host="${BOSCA_REGISTRY_URL#http://}"
registry_host="${registry_host#https://}"
registry_host="${registry_host%%/*}"
export HELM_OCI_URL="oci://$registry_host/bosca-charts"
# Supply HELM_REPO_TOKEN, HELM_OCI_USERNAME and HELM_OCI_TOKEN through your secret manager.
bash helm/scripts/release.sh publish --output /tmp/bosca-helm-packages
```

`HELM_REPO_TOKEN` falls back to `BOSCA_REGISTRY_TOKEN`. If OCI credentials are
omitted, Helm uses its existing registry login. `HELM_OCI_URL` can also point to
another OCI registry; pass that registry's credentials. Set
`HELM_OCI_PLAIN_HTTP=true` only for a registry using HTTP, such as the disposable
localhost registry in the integration test.

The shell tests exercise conflict handling and partial-release retries using
isolated command fixtures. They also package a chart with a local dependency
using Helm and check release versions, image defaults, explicit overrides and
source preservation. The version tests cover upstream dependency pins and
third-party image defaults.
Rebuilt releases and retries with only one populated destination reuse the
published bytes; changed templates and bundled dependencies are rejected.
