package bosca.kubernetes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire request body for `POST /clusters/{id}/workloads/{kind}/{namespace}/{name}/scale`.
 * Single-field DTO so the schema can grow without breaking older
 * controllers (e.g. adding a server-side dry-run flag later).
 */
@Serializable
data class ScaleWorkloadRequest(
    val replicas: Int,
)

/**
 * Wire request body for `POST /clusters/{id}/namespaces`.
 *
 * `labels` is an opaque JSON map mirroring the GraphQL `Labels` scalar
 * — passed through to `metadata.labels` on the new namespace.
 */
@Serializable
data class CreateNamespaceRequest(
    val name: String,
    val labels: JsonElement? = null,
)

/**
 * Wire request body for `POST /clusters/{id}/apply`.
 *
 * `manifest` is a raw YAML document or multi-document stream (`---`
 * separated). `dryRun=true` performs a server-side dry-run and
 * populates [ApplyResult.dryRun] without mutating cluster state.
 */
@Serializable
data class ApplyManifestRequest(
    val manifest: String,
    val dryRun: Boolean = false,
)

/** Wire shape for the apply mutation's result. Mirrors GraphQL `ApplyResult`. */
@Serializable
data class ApplyResult(
    val succeeded: Boolean,
    val applied: List<String>,
    val failed: List<ApplyFailure>,
    val dryRun: String? = null,
)

/** Wire shape for a single resource that failed to apply. */
@Serializable
data class ApplyFailure(
    val resource: String,
    val error: String,
)

/**
 * Wire envelope for the delete route response. A boolean by itself
 * leaves no room for diagnostic context if a delete partially
 * succeeds (cascade orphans, finalizers); the [details] field is
 * reserved for future expansion.
 */
@Serializable
data class DeleteResponse(
    val deleted: Boolean,
    val details: String? = null,
)
