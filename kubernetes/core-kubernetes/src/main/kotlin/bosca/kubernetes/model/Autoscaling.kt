package bosca.kubernetes.model

import kotlinx.serialization.Serializable

/**
 * A HorizontalPodAutoscaler (autoscaling/v2). Mirrors the GraphQL
 * `HorizontalPodAutoscaler` type.
 *
 * `ableToScale` / `scalingActive` / `scalingLimited` are lifted from
 * `status.conditions` so the studio can derive a status tone without
 * re-parsing condition arrays. Absent conditions (freshly created
 * autoscaler, controller not yet reconciled) default to the neutral
 * `ableToScale=true, scalingActive=true, scalingLimited=false` so a
 * new HPA doesn't render as failing before its first sync.
 */
@Serializable
data class K8sHpa(
    val id: String,
    val name: String,
    val namespace: String,
    val targetKind: String,
    val targetName: String,
    val minReplicas: Int,
    val maxReplicas: Int,
    val currentReplicas: Int,
    val desiredReplicas: Int,
    val metrics: List<K8sHpaMetric>,
    val ableToScale: Boolean,
    val scalingActive: Boolean,
    val scalingLimited: Boolean,
    val lastScaleTime: String? = null,
    val age: String,
)

/**
 * One metric rule on an HPA, flattened to display strings so the
 * studio renders `current / target` without knowing the five
 * autoscaling/v2 metric source shapes. `current` is null until the
 * metrics pipeline reports a sample for the rule.
 */
@Serializable
data class K8sHpaMetric(
    val label: String,
    val current: String? = null,
    val target: String,
)

/** Wire envelope for `GET /clusters/{id}/hpas`. */
@Serializable
data class HpasResponse(val items: List<K8sHpa>)

/**
 * A PodDisruptionBudget (policy/v1). Mirrors the GraphQL
 * `PodDisruptionBudget` type. Exactly one of `minAvailable` /
 * `maxUnavailable` is set on a valid PDB; both are kept as strings
 * because the API accepts integers and percentages (`2`, `50%`).
 */
@Serializable
data class K8sPdb(
    val id: String,
    val name: String,
    val namespace: String,
    val minAvailable: String? = null,
    val maxUnavailable: String? = null,
    val currentHealthy: Int,
    val desiredHealthy: Int,
    val disruptionsAllowed: Int,
    val expectedPods: Int,
    val selector: String,
    val age: String,
)

/** Wire envelope for `GET /clusters/{id}/pdbs`. */
@Serializable
data class PdbsResponse(val items: List<K8sPdb>)

/**
 * Wire request body for
 * `POST /clusters/{id}/hpas/{namespace}/{name}/limits`. Only the
 * replica bounds are editable through the quick path — metric rules
 * and behavior policies go through the YAML editor.
 */
@Serializable
data class UpdateHpaLimitsRequest(
    val minReplicas: Int,
    val maxReplicas: Int,
)
