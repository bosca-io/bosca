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

## Distributed cache and locks

The infrastructure chart pins Dragonfly to `v2.0.2`. Set `redis.image` in your
deployment's infrastructure values to override the full image reference:

```yaml
redis:
  image: docker.dragonflydb.io/dragonflydb/dragonfly:v2.0.2
```

The chart passes this image to the Dragonfly operator through `spec.image`.
Apply it with the existing infrastructure release name, namespace and values file.

Starting with services chart `0.1.3`, server, runner, collector, artifacts server,
Git server, and Kubernetes controller charts expose `appConfig.cacheType` and
`appConfig.distributedLockType`, each accepting `nats` or `redis`. In the services
umbrella, set these under each service's values. For example:

```yaml
bosca-server:
  appConfig:
    cacheType: redis
    distributedLockType: redis
    redisHost: redis
    redisPort: 6379
```

`redisHost` names a Service in the release namespace. Apply the same backend and
Redis endpoint settings to every service sharing caches or locks. All six charts
default to NATS for both backends. These settings do not change job queues,
pub/sub, or counters.

When changing an existing deployment's lock backend, stop all consumers of the
shared locks before starting them with the new configuration. NATS and Redis
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
`PIPELINE_SECRET_KEY` as the main server and runner. The umbrella defaults Atlas's
embedded workers/listeners/scheduler off because the platform runner already owns
them. A standalone chart defaults to embedded workers; `runner.enabled`,
`runner.queues`, `runner.concurrency`, `runner.listeners` and `runner.scheduler`
select that responsibility.

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

Bosca application `appVersion` defaults and explicit Bosca image tags (including
the model loader sidecar) also use the release tag. Bosca images are identified
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
