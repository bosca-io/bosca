# Bosca Helm charts

Public OCI chart releases use GitHub Container Registry:
`oci://ghcr.io/bosca-io/bosca/charts/<chart-name>`. Each release publishes the same
chart packages through both a conventional Helm repository and OCI.

## Install or upgrade from GitHub

Deploy infrastructure first, then application services, using your deployment's
values files:

```bash
helm upgrade --install bosca-infra oci://ghcr.io/bosca-io/bosca/charts/bosca-infra --version 0.1.0 -f infra-values.yaml
helm upgrade --install bosca-services oci://ghcr.io/bosca-io/bosca/charts/bosca-services --version 0.1.0 -f services-values.yaml
```

For an existing deployment, use its current release names, namespaces and values
files. Update the chart source to the public GitHub location. Moving from a 6.x
or 7.x version to 0.1.0 requires an explicit version selection; existing semver
ranges do not automatically select the smaller version.

## Maintainer release process

`.bosca/pipelines/release-helm.yaml` publishes each chart to Bosca Artifacts' HTTP
Helm repository and OCI registry in namespace `bosca-charts`. Run it against an
existing Git tag. Chart versions
come from each `Chart.yaml`, independently of the Git tag and `appVersion`; the
pipeline does not change version numbers.

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

The publisher builds dependencies from checked-in locks in a temporary copy,
lints and renders every application chart, then packages each chart once.
Dependencies are included in the packages; development `file://` references are
retained in their metadata.
Consumers install the complete packages without rebuilding dependencies. The
same archive goes to both destinations, and downloaded SHA-256 checksums are
verified after publication. Source charts, values and dependency locks remain
untouched. The old `prepare-for-publish.sh` helper is not used by this pipeline.

Before uploading, the publisher checks all versions at both destinations.
Identical existing versions are accepted; differing contents fail the release.
The package directory contains the archives and a `release.tsv` checksum
manifest. A failure between destinations can leave a partial release: retain
that directory and rerun `publish` with those exact archives to finish it.
Changed charts need a new version rather than replacing an existing release.
Publication across the two registries is not atomic; keep other publishers from
writing the same versions while a release runs.

## Local packaging and publishing

Prerequisites: Bash, Helm 3 with OCI support, curl and standard shell utilities.
SHA-256 checks use `sha256sum` or `shasum`. The release pipeline
uses Helm 3.22.0. From the workspace root:

```bash
bash helm/scripts/release-test.sh
bash helm/scripts/release.sh package --output /tmp/bosca-helm-packages

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
using Helm and check that source files and chart versions remain untouched.
