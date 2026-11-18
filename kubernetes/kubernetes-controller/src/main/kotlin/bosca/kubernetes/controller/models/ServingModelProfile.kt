package bosca.kubernetes.controller.models

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.utils.Serialization

/** Native pod settings and discovery credentials for independently loaded model generations. */
data class ServingModelProfile(val resource: GenericKubernetesResource) {
    val namespace: String = requireNotNull(resource.metadata.namespace)
    val name: String = requireNotNull(resource.metadata.name)
    val uid: String = requireNotNull(resource.metadata.uid)
    private val spec = resource.additionalProperties["spec"] as? Map<*, *> ?: error("ServingModelProfile spec is required")
    val boscaUrl: String = spec["boscaUrl"] as? String ?: error("ServingModelProfile boscaUrl is required")
    val tokenSecretName: String = spec["tokenSecretName"] as? String ?: error("ServingModelProfile tokenSecretName is required")
    val tokenSecretKey: String = spec["tokenSecretKey"] as? String ?: "artifacts_pull_token"
    val controlTokenSecretName: String = spec["controlTokenSecretName"] as? String ?: error("ServingModelProfile controlTokenSecretName is required")
    val controlTokenSecretKey: String = spec["controlTokenSecretKey"] as? String ?: "bosca_token"
    val failureGraceSeconds: Long = (spec["failureGraceSeconds"] as? Number)?.toLong() ?: 600
    val startupTimeoutSeconds: Long = (spec["startupTimeoutSeconds"] as? Number)?.toLong() ?: 1_800
    val serviceNamePrefix: String = spec["serviceNamePrefix"] as? String ?: "$name-"
    val servicePort: Int = (spec["servicePort"] as? Number)?.toInt() ?: 8501
    val modelBasePath: String = spec["modelBasePath"] as? String ?: "/models"
    val drainSeconds: Long = (spec["drainSeconds"] as? Number)?.toLong() ?: 120
    val maxCandidates: Int = (spec["maxCandidates"] as? Number)?.toInt() ?: 1
    val podTemplate: PodTemplateSpec = Serialization.unmarshal(Serialization.asJson(spec["podTemplate"]), PodTemplateSpec::class.java)

    init {
        require(Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?-").matches(serviceNamePrefix) && serviceNamePrefix.length <= 42) {
            "ServingModelProfile serviceNamePrefix must be a DNS label ending in '-' with at most 42 characters"
        }
        require(servicePort in 1..65535 && drainSeconds >= 0 && failureGraceSeconds >= 0 && startupTimeoutSeconds > 0 && maxCandidates >= 1)
        require(modelBasePath.startsWith('/') && '"' !in modelBasePath && '\n' !in modelBasePath)
        require(podTemplate.spec?.containers.orEmpty().isNotEmpty()) { "ServingModelProfile requires a serving container" }
        require(podTemplate.spec?.volumes.orEmpty().none { it.persistentVolumeClaim != null }) {
            "ServingModelProfile requires per-Pod storage; a shared PVC cannot be reused across generations"
        }
        require(podTemplate.spec?.initContainers.orEmpty().any { it.name == "download-models" }) {
            "ServingModelProfile requires the fixed-version download-models init container"
        }
    }

    fun modelName(version: Long): String = "$serviceNamePrefix$version"
    fun endpoint(version: Long): String = "http://${modelName(version)}.$namespace.svc:$servicePort"
}

internal const val SERVING_API = "kubernetes.bosca.io/v1alpha1"
internal const val SERVING_PROFILE_LABEL = "kubernetes.bosca.io/serving-profile-uid"
internal const val SERVING_MODEL_LABEL = "kubernetes.bosca.io/serving-model-uid"
internal const val DRAIN_SINCE = "kubernetes.bosca.io/drain-since"
internal const val PREPARED_REVISION = "kubernetes.bosca.io/prepared-revision"
internal const val STARTUP_SINCE = "kubernetes.bosca.io/startup-since"
internal const val SELECTION_REVISION = "kubernetes.bosca.io/selection-revision"
internal const val FAILURE_SINCE = "kubernetes.bosca.io/failure-since"
internal val SERVING_PROFILES: ResourceDefinitionContext = servingResource("ServingModelProfile", "servingmodelprofiles")
internal val SERVING_MODELS: ResourceDefinitionContext = servingResource("ServingModel", "servingmodels")

private fun servingResource(kind: String, plural: String): ResourceDefinitionContext = ResourceDefinitionContext.Builder()
    .withGroup("kubernetes.bosca.io").withVersion("v1alpha1").withKind(kind).withPlural(plural).withNamespaced(true).build()
