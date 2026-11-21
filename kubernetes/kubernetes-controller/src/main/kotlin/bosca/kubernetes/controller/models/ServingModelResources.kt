package bosca.kubernetes.controller.models

import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Captures immutable pod settings so profile edits cannot replace an already loaded generation. */
fun buildServingModel(profile: ServingModelProfile, export: ServingModelExport): GenericKubernetesResource {
    require(export.version > 0)
    require(export.models.keys.all { Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*").matches(it) })
    return GenericKubernetesResource().apply {
        apiVersion = SERVING_API
        kind = "ServingModel"
        metadata = ObjectMetaBuilder().withName(profile.modelName(export.version)).withNamespace(profile.namespace)
            .addToLabels(SERVING_PROFILE_LABEL, profile.uid)
            .withOwnerReferences(owner(profile.resource)).build()
        additionalProperties["spec"] = mapOf(
            "profileName" to profile.name, "version" to export.version, "contextId" to export.contextId,
            "models" to export.models, "servicePort" to profile.servicePort,
            "modelBasePath" to profile.modelBasePath,
            "podTemplate" to Serialization.jsonMapper().readValue(Serialization.asJson(profile.podTemplate), Map::class.java),
        )
    }
}

/** A model owns its Pod, exact config and private Service; there is no Deployment or Kubernetes Job. */
class ServingModelResources(val model: GenericKubernetesResource) {
    val name: String = requireNotNull(model.metadata.name)
    val namespace: String = requireNotNull(model.metadata.namespace)
    val uid: String = requireNotNull(model.metadata.uid)
    private val spec = model.additionalProperties["spec"] as? Map<*, *> ?: error("ServingModel spec is required")
    val version: Long = (spec["version"] as Number).toLong()
    val port: Int = (spec["servicePort"] as Number).toInt()
    val endpoint: String = "http://$name.$namespace.svc:$port"
    private val basePath = spec["modelBasePath"] as String
    private val models = (spec["models"] as Map<*, *>).entries.associate { (name, version) ->
        (name as String) to (version as Number).toLong()
    }
    private val labels = mapOf(SERVING_MODEL_LABEL to uid)

    fun configMap(): ConfigMap = ConfigMapBuilder().withMetadata(metadata()).addToData("models.config", buildString {
        appendLine("model_config_list {")
        models.forEach { (name, selected) ->
            require(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*").matches(name) && selected == version)
            appendLine("  config { name: \"$name\" base_path: \"$basePath/$name\" model_platform: \"tensorflow\" model_version_policy { specific { versions: $selected } } }")
        }
        appendLine("}")
    }).build()

    fun service(): Service = ServiceBuilder().withMetadata(metadata()).withNewSpec().addToSelector(labels)
        .addNewPort().withName("http").withPort(port).withTargetPort(IntOrString("http")).endPort()
        .endSpec().build()

    fun pod(): Pod {
        val template = Serialization.unmarshal(Serialization.asJson(spec["podTemplate"]), PodTemplateSpec::class.java)
        val podSpec = requireNotNull(template.spec)
        podSpec.restartPolicy = "Always"
        val loader = podSpec.initContainers.first { it.name == "download-models" }
        val environment = mapOf(
            "MODEL_VERSIONS_JSON" to Json.encodeToString(JsonObject.serializer(), JsonObject(models.mapValues { JsonPrimitive(it.value) })),
            "MODELS_ROOT" to basePath,
        )
        loader.env = loader.env.orEmpty().filterNot { it.name in environment } +
            environment.map { (name, value) -> EnvVarBuilder().withName(name).withValue(value).build() }
        podSpec.volumes = podSpec.volumes.orEmpty().filterNot { it.name == "model-config" } +
            VolumeBuilder().withName("model-config").withNewConfigMap().withName(name).endConfigMap().build()
        return PodBuilder().withMetadata(metadata().also {
            it.labels = template.metadata?.labels.orEmpty() + labels
            it.annotations = template.metadata?.annotations.orEmpty()
        }).withSpec(podSpec).build()
    }

    fun owns(resource: HasMetadata): Boolean = resource.metadata?.ownerReferences.orEmpty().any { it.uid == uid && it.controller == true }

    private fun metadata() = ObjectMetaBuilder().withName(name).withNamespace(namespace).addToLabels(labels)
        .withOwnerReferences(owner(model)).build()
}

private fun owner(resource: HasMetadata) = OwnerReferenceBuilder()
    .withApiVersion(resource.apiVersion).withKind(resource.kind).withName(resource.metadata.name)
    .withUid(resource.metadata.uid).withController(true).withBlockOwnerDeletion(true).build()
