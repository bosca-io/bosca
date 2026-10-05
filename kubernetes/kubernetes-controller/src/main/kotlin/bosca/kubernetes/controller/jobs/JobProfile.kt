package bosca.kubernetes.controller.jobs

import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.serialization.UUID
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import java.security.MessageDigest

/**
 * Validated view of one `JobProfile` custom resource.
 *
 * The pod template remains a native Kubernetes [PodTemplateSpec], so tolerations, affinity,
 * runtime classes, volumes, and extended GPU resources pass through without a parallel Bosca API.
 */
data class JobProfile(
    val resource: GenericKubernetesResource,
    val namespace: String,
    val name: String,
    val uid: String,
    val maxParallelism: Int,
    val workerContainerName: String,
    val backoffLimit: Int,
    val ttlSecondsAfterFinished: Int?,
    val activeDeadlineSeconds: Long,
    val startupFailureGraceSeconds: Long,
    val podTemplate: PodTemplateSpec,
) {

    companion object {
        fun from(resource: GenericKubernetesResource): JobProfile {
            val metadata = resource.metadata ?: error("$KIND metadata is required")
            val namespace = metadata.namespace?.takeIf { it.isNotBlank() }
                ?: error("$KIND metadata.namespace is required")
            val name = metadata.name?.takeIf { it.isNotBlank() }
                ?: error("$KIND metadata.name is required")
            val uid = metadata.uid?.takeIf { it.isNotBlank() }
                ?: error("$KIND metadata.uid is required")
            val spec = resource.additionalProperties.stringMap("spec")
            val podTemplateMap = spec.stringMap("podTemplate")
            val podTemplate = Serialization.unmarshal(
                Serialization.asJson(podTemplateMap),
                PodTemplateSpec::class.java,
            )
            val maxParallelism = spec.int("maxParallelism", 1)
            require(maxParallelism in 1..10_000) {
                "$KIND $namespace/$name maxParallelism must be between 1 and 10000"
            }
            val workerContainerName = spec.string("workerContainerName", "worker")
            require(workerContainerName.isNotBlank()) {
                "$KIND $namespace/$name workerContainerName must not be blank"
            }
            require(podTemplate.spec?.containers.orEmpty().any { it.name == workerContainerName }) {
                "$KIND $namespace/$name podTemplate must contain '$workerContainerName'"
            }
            val job = spec.optionalStringMap("job")
            val backoffLimit = job?.int("backoffLimit", 0) ?: 0
            val ttlSecondsAfterFinished = job?.optionalInt("ttlSecondsAfterFinished")
            val activeDeadlineSeconds =
                job?.optionalLong("activeDeadlineSeconds") ?: DEFAULT_ACTIVE_DEADLINE_SECONDS
            val startupFailureGraceSeconds =
                job?.optionalLong("startupFailureGraceSeconds") ?: DEFAULT_STARTUP_FAILURE_GRACE_SECONDS
            require(backoffLimit >= 0) {
                "$KIND $namespace/$name backoffLimit must not be negative"
            }
            require(ttlSecondsAfterFinished == null || ttlSecondsAfterFinished >= 0) {
                "$KIND $namespace/$name ttlSecondsAfterFinished must not be negative"
            }
            require(activeDeadlineSeconds > 0) {
                "$KIND $namespace/$name activeDeadlineSeconds must be positive"
            }
            require(startupFailureGraceSeconds >= 0) {
                "$KIND $namespace/$name startupFailureGraceSeconds must not be negative"
            }

            return JobProfile(
                resource = resource,
                namespace = namespace,
                name = name,
                uid = uid,
                maxParallelism = maxParallelism,
                workerContainerName = workerContainerName,
                backoffLimit = backoffLimit,
                ttlSecondsAfterFinished = ttlSecondsAfterFinished,
                activeDeadlineSeconds = activeDeadlineSeconds,
                startupFailureGraceSeconds = startupFailureGraceSeconds,
                podTemplate = podTemplate,
            )
        }
    }
}

/**
 * Builds one deterministic Kubernetes Job by combining [profile] with per-run [request] values.
 */
fun buildKubernetesJob(
    profile: JobProfile,
    request: KubernetesJobRequest,
    dispatchId: UUID,
): Job {
    val template = Serialization.clone(profile.podTemplate)
    val templateMetadata = template.metadata ?: ObjectMetaBuilder().build().also { template.metadata = it }
    templateMetadata.labels = templateMetadata.labels.orEmpty() + request.labels + managedLabels(profile, dispatchId)
    templateMetadata.annotations =
        templateMetadata.annotations.orEmpty() + request.annotations + managedAnnotations(request, dispatchId)

    val podSpec = template.spec
        ?: error("$KIND ${profile.namespace}/${profile.name} podTemplate.spec is required")
    podSpec.restartPolicy = "Never"
    val worker = podSpec.containers.first { it.name == profile.workerContainerName }
    if (worker.terminationMessagePolicy.isNullOrBlank()) {
        worker.terminationMessagePolicy = "FallbackToLogsOnError"
    }
    val managedEnvironment = request.environment + (DISPATCH_ID_ENV to dispatchId.toString())
    worker.env = worker.env.orEmpty()
        .filterNot { it.name in managedEnvironment }
        .plus(managedEnvironment.map { (name, value) ->
            EnvVarBuilder().withName(name).withValue(value).build()
        })
    request.arguments?.let { worker.args = it }

    val owner = OwnerReferenceBuilder()
        .withApiVersion(API_VERSION)
        .withKind(KIND)
        .withName(profile.name)
        .withUid(profile.uid)
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build()
    val jobLabels: Map<String, String> = request.labels + managedLabels(profile, dispatchId)
    val jobAnnotations: Map<String, String> = request.annotations + managedAnnotations(request, dispatchId)
    val metadata = ObjectMetaBuilder()
        .withName(kubernetesJobName(profile.name, request.idempotencyKey))
        .withNamespace(profile.namespace)
        .addToLabels(jobLabels)
        .addToAnnotations(jobAnnotations)
        .withOwnerReferences(owner)
        .withFinalizers(JOB_RESULT_FINALIZER)
        .build()
    val specBuilder = JobSpecBuilder()
        .withBackoffLimit(profile.backoffLimit)
        .withTemplate(template)
    profile.ttlSecondsAfterFinished?.let(specBuilder::withTtlSecondsAfterFinished)
    specBuilder.withActiveDeadlineSeconds(profile.activeDeadlineSeconds)

    return JobBuilder()
        .withMetadata(metadata)
        .withSpec(specBuilder.build())
        .build()
}

internal fun kubernetesJobName(profile: String, idempotencyKey: String): String =
    "${dnsName(profile)}-${sha256("$profile\u0000$idempotencyKey").take(16)}"

internal fun isExpectedJob(
    job: Job,
    request: KubernetesJobRequest,
    dispatchId: UUID,
): Boolean {
    val annotations = job.metadata?.annotations ?: return false
    return annotations[IDEMPOTENCY_KEY_ANNOTATION] == request.idempotencyKey &&
        annotations[DISPATCH_ID_ANNOTATION] == dispatchId.toString()
}

internal const val API_GROUP = "kubernetes.bosca.io"
internal const val API_VERSION_NAME = "v1alpha1"
internal const val API_VERSION = "$API_GROUP/$API_VERSION_NAME"
internal const val KIND = "JobProfile"
internal const val PLURAL = "jobprofiles"
internal const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
internal const val MANAGED_BY_VALUE = "bosca-kubernetes-jobs"
internal const val PROFILE_LABEL = "kubernetes.bosca.io/job-profile"
internal const val PROFILE_UID_LABEL = "kubernetes.bosca.io/job-profile-uid"
internal const val DISPATCH_ID_LABEL = "kubernetes.bosca.io/dispatch-id"
internal const val DISPATCH_ID_ANNOTATION = "kubernetes.bosca.io/dispatch-id"
internal const val IDEMPOTENCY_KEY_ANNOTATION = "kubernetes.bosca.io/idempotency-key"
internal const val DISPATCH_ID_ENV = "BOSCA_KUBERNETES_JOB_ID"
internal const val JOB_RESULT_FINALIZER = "kubernetes.bosca.io/persist-result"
internal const val DEFAULT_ACTIVE_DEADLINE_SECONDS = 86_400L
internal const val DEFAULT_STARTUP_FAILURE_GRACE_SECONDS = 120L

private fun managedLabels(profile: JobProfile, dispatchId: UUID) = mapOf(
    MANAGED_BY_LABEL to MANAGED_BY_VALUE,
    PROFILE_LABEL to kubernetesLabelValue(profile.name),
    PROFILE_UID_LABEL to kubernetesLabelValue(profile.uid),
    DISPATCH_ID_LABEL to kubernetesLabelValue(dispatchId.toString()),
)

private fun managedAnnotations(request: KubernetesJobRequest, dispatchId: UUID) = mapOf(
    DISPATCH_ID_ANNOTATION to dispatchId.toString(),
    IDEMPOTENCY_KEY_ANNOTATION to request.idempotencyKey,
)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun dnsName(value: String): String {
    val sanitized = value.lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .take(40)
        .trimEnd('-')
    return sanitized.ifBlank { "job" }
}

internal fun kubernetesLabelValue(value: String): String {
    val sanitized = value
        .replace(Regex("[^A-Za-z0-9_.-]+"), "-")
        .trim('-', '_', '.')
        .take(63)
        .trimEnd('-', '_', '.')
    return sanitized.ifBlank { "job" }
}

private fun Map<String, Any?>.string(name: String, default: String): String =
    (this[name] as? String) ?: default

private fun Map<String, Any?>.int(name: String, default: Int): Int =
    optionalInt(name) ?: default

private fun Map<String, Any?>.optionalInt(name: String): Int? = (this[name] as? Number)?.toInt()

private fun Map<String, Any?>.optionalLong(name: String): Long? = (this[name] as? Number)?.toLong()

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.stringMap(name: String): Map<String, Any?> =
    this[name] as? Map<String, Any?> ?: error("$KIND $name is required")

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.optionalStringMap(name: String): Map<String, Any?>? =
    this[name] as? Map<String, Any?>
