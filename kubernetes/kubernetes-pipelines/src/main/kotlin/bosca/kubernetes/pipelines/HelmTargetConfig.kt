package bosca.kubernetes.pipelines

import kotlinx.serialization.Serializable

/**
 * The Helm-specific settings a [HelmDeployTarget] decodes from a `DeployRequest.config`.
 * All fields are plain strings so the config is a portable JSON object on the deploy node (no
 * contextual serializers). Values come either inline ([values]) or — matching today's "edit the
 * `values.yaml` in the ops repo" flow — from a git file ([valuesRepositoryId]/[valuesRef]/[valuesPath]).
 */
@Serializable
data class HelmTargetConfig(
    /** Target cluster UUID (string form). */
    val clusterId: String,
    /** Helm release name. */
    val releaseName: String,
    /** Kubernetes namespace. */
    val namespace: String,
    /** Helm repo name. */
    val repo: String,
    /** Chart name within the repo. */
    val chart: String,
    /** Chart version; blank falls back to the request's release version. */
    val chartVersion: String = "",
    /** Discard the release's previously-set values on upgrade. */
    val resetValues: Boolean = false,
    /** Git repo UUID to read `values.yaml` from (string form); blank uses [values]. */
    val valuesRepositoryId: String = "",
    /** Git ref to read the values from. */
    val valuesRef: String = "refs/heads/main",
    /** Values file path within the git repo. */
    val valuesPath: String = "values.yaml",
    /** Inline values (takes precedence over the git source when non-blank). */
    val values: String = "",
    /**
     * Flux-style version automation: dot-paths in the values file (e.g. `image.tag`) whose scalars are
     * set to the deploying version before the upgrade. Git-sourced values get the bump COMMITTED back
     * to the [valuesRef] branch, so git always reflects what's deployed; inline [values] are rewritten
     * in place (nothing to commit). Empty = the values are used as-is.
     */
    val versionPaths: List<String> = emptyList(),
)
