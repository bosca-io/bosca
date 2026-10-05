package bosca.kubernetes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Coarse status of a helm release. Matches the GraphQL `HelmStatus` enum. */
@Serializable
enum class HelmStatus { DEPLOYED, PENDING, FAILED, SUPERSEDED, UNINSTALLED }

/**
 * A configured Helm chart repository. `type` is `http` for normal
 * HTTPS-served `index.yaml` repos and `oci` for OCI registry-style
 * repos; v1 of the reader path serves only `http` repos — `oci` is
 * present in the wire shape so the schema doesn't change when OCI
 * support lands.
 */
@Serializable
data class K8sHelmRepo(
    val name: String,
    val url: String,
    val type: String,
    val charts: Int,
    val lastUpdate: String,
)

@Serializable
data class HelmReposResponse(val items: List<K8sHelmRepo>)

/**
 * Plaintext HTTP Basic credentials for a private Helm repository.
 * Instances are short-lived and must never be persisted directly or logged.
 */
@Serializable
class HelmRepoCredentials(
    val username: String,
    val password: String,
)

/**
 * A chart entry in a configured repository's index. The `latestVersion`
 * is the first entry in the index for this chart; iterating to a
 * specific older version uses [K8sHelmChartVersion].
 */
@Serializable
data class K8sHelmChart(
    val id: String,
    val name: String,
    val repo: String,
    val version: String,
    val appVersion: String,
    val description: String,
    val icon: String? = null,
)

@Serializable
data class HelmChartsResponse(val items: List<K8sHelmChart>)

@Serializable
data class K8sHelmChartVersion(
    val version: String,
    val appVersion: String,
    val released: String,
    val current: Boolean,
)

@Serializable
data class HelmChartVersionsResponse(val items: List<K8sHelmChartVersion>)

/**
 * Default values and optional values schema for a chart version. The
 * schema is the parsed contents of `values.schema.json` (when the
 * chart ships one) — null otherwise.
 */
@Serializable
data class K8sHelmChartValues(
    val defaultValues: String,
    val schema: JsonElement? = null,
)

/**
 * An installed helm release as stored in the cluster's release
 * Secrets. `chart` is the chart's bare name (e.g. `cnpg`),
 * `chartVersion` is its specific version (e.g. `0.21.0`), and
 * `repo`/`repoUrl` hydrate from the index when we can match the chart
 * back to a known repo. Unknown-origin releases (installed without a
 * configured repo) return empty strings for those fields rather than
 * null so the studio renders a dash without nullable handling.
 */
@Serializable
data class K8sHelmRelease(
    val id: String,
    val name: String,
    val namespace: String,
    val chart: String,
    val chartVersion: String,
    val appVersion: String,
    val revision: Int,
    val status: HelmStatus,
    val updated: String,
    val installed: String,
    val repo: String,
    val repoUrl: String,
    val description: String,
)

@Serializable
data class HelmReleasesResponse(val items: List<K8sHelmRelease>)

@Serializable
data class K8sHelmRevision(
    val revision: Int,
    val updated: String,
    val status: HelmStatus,
    val chart: String,
    val appVersion: String,
    val description: String,
)

@Serializable
data class HelmReleaseHistoryResponse(val items: List<K8sHelmRevision>)

/**
 * Wire response for `GET /clusters/{id}/helm/releases/{namespace}/{name}/values`
 * and the sibling `/manifest` endpoint. Wraps a single YAML payload so
 * the controller's route layer doesn't need a bespoke string serializer.
 */
@Serializable
data class HelmReleaseTextResponse(val yaml: String)

/** Request body for `POST /helm/repos`. */
@Serializable
data class HelmRepoAddRequest(
    val name: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
) {
    /** Returns credentials when either Basic-auth component was supplied. */
    fun credentials(): HelmRepoCredentials? =
        if (username == null && password == null) null
        else HelmRepoCredentials(username.orEmpty(), password.orEmpty())
}
