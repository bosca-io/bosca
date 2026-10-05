package bosca.kubernetes.controller.jobs

import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.serialization.UUID
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.TolerationBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobProfileTest {

    @Test
    fun `job combines request values with native scheduling profile`() {
        val profile = JobProfile.from(resource())
        val request = KubernetesJobRequest(
            profile = "training",
            idempotencyKey = "recommendations-model-42",
            environment = mapOf(
                "TRAINING_RUN_ID" to "42",
                DISPATCH_ID_ENV to "cannot-override",
            ),
            arguments = listOf("train", "--run-id", "42"),
            labels = mapOf(
                "model" to "recommendations",
                MANAGED_BY_LABEL to "cannot-override",
            ),
            annotations = mapOf("example.io/source" to "test"),
        )
        val dispatchId = UUID.random()
        val job = buildKubernetesJob(profile, request, dispatchId)
        val pod = assertNotNull(job.spec?.template?.spec)
        val worker = pod.containers.single()

        assertEquals("gpu", pod.nodeSelector["workload"])
        assertEquals("dedicated", pod.tolerations.single().key)
        assertEquals("training", pod.tolerations.single().value)
        assertEquals(Quantity("1"), worker.resources.limits["nvidia.com/gpu"])
        assertEquals("Never", pod.restartPolicy)
        assertEquals("FallbackToLogsOnError", worker.terminationMessagePolicy)
        assertEquals("42", worker.env.first { it.name == "TRAINING_RUN_ID" }.value)
        assertEquals(dispatchId.toString(), worker.env.first { it.name == DISPATCH_ID_ENV }.value)
        assertEquals(listOf("train", "--run-id", "42"), worker.args)
        assertEquals("recommendations-model-42", job.metadata.annotations[IDEMPOTENCY_KEY_ANNOTATION])
        assertEquals("recommendations", job.metadata.labels["model"])
        assertEquals(MANAGED_BY_VALUE, job.metadata.labels[MANAGED_BY_LABEL])
        assertEquals("training", job.metadata.ownerReferences.single().name)
        assertEquals(0, job.spec.backoffLimit)
        assertEquals(3600, job.spec.ttlSecondsAfterFinished)
        assertEquals(21_600, job.spec.activeDeadlineSeconds)
        assertEquals(120, profile.startupFailureGraceSeconds)
        assertEquals(listOf(JOB_RESULT_FINALIZER), job.metadata.finalizers)
    }

    @Test
    fun `job preserves an explicit worker termination message policy`() {
        val profile = JobProfile.from(resource())
        profile.podTemplate.spec.containers.single().terminationMessagePolicy = "File"

        val job = buildKubernetesJob(
            profile,
            KubernetesJobRequest("training", "explicit-termination-policy"),
            UUID.random(),
        )

        assertEquals(
            "File",
            job.spec.template.spec.containers.single().terminationMessagePolicy,
        )
    }

    @Test
    fun `same idempotency key produces the same kubernetes job name`() {
        val first = kubernetesJobName("android", "ci-job-123-attempt-1")
        val second = kubernetesJobName("android", "ci-job-123-attempt-1")
        val different = kubernetesJobName("android", "ci-job-123-attempt-2")

        assertEquals(first, second)
        assertNotEquals(first, different)
    }

    @Test
    fun `profiles with the same sanitized prefix produce different kubernetes job names`() {
        val dotted = kubernetesJobName("gpu.a", "training-42")
        val dashed = kubernetesJobName("gpu-a", "training-42")

        assertNotEquals(dotted, dashed)
    }

    @Test
    fun `profile rejects a missing worker container`() {
        assertFailsWith<IllegalArgumentException> {
            JobProfile.from(resource(workerContainerName = "missing"))
        }
        val withoutPodSpec = resource().also {
            val spec = it.spec()
            @Suppress("UNCHECKED_CAST")
            val template = spec["podTemplate"] as Map<String, Any?>
            it.additionalProperties["spec"] = spec + ("podTemplate" to (template - "spec"))
        }
        assertFailsWith<IllegalArgumentException> {
            JobProfile.from(withoutPodSpec)
        }
    }

    @Test
    fun `profile rejects invalid job limits`() {
        val resource = resource()
        @Suppress("UNCHECKED_CAST")
        val spec = resource.additionalProperties["spec"] as Map<String, Any?>
        resource.additionalProperties["spec"] = spec + (
            "job" to mapOf("backoffLimit" to -1)
        )

        assertFailsWith<IllegalArgumentException> {
            JobProfile.from(resource)
        }
    }

    @Test
    fun `profile requires complete Kubernetes identity and pod template`() {
        listOf("namespace", "name", "uid").forEach { field ->
            val resource = resource()
            resource.metadata = ObjectMetaBuilder(resource.metadata).also { builder ->
                when (field) {
                    "namespace" -> builder.withNamespace("")
                    "name" -> builder.withName("")
                    "uid" -> builder.withUid("")
                }
            }.build()

            assertFailsWith<IllegalStateException>(field) { JobProfile.from(resource) }
        }

        val missingSpec = resource().also { it.additionalProperties.remove("spec") }
        assertFailsWith<IllegalStateException> { JobProfile.from(missingSpec) }

        val missingTemplate = resource().also {
            it.additionalProperties["spec"] = it.spec() - "podTemplate"
        }
        assertFailsWith<IllegalStateException> { JobProfile.from(missingTemplate) }

        assertFailsWith<IllegalStateException> {
            JobProfile.from(resource().also { it.metadata = null })
        }
        listOf("namespace", "name", "uid").forEach { field ->
            val resource = resource()
            resource.metadata = ObjectMetaBuilder(resource.metadata).also { builder ->
                when (field) {
                    "namespace" -> builder.withNamespace(null)
                    "name" -> builder.withName(null)
                    "uid" -> builder.withUid(null)
                }
            }.build()
            assertFailsWith<IllegalStateException>(field) { JobProfile.from(resource) }
        }
    }

    @Test
    fun `profile validates every numeric and worker limit`() {
        fun invalid(vararg values: Pair<String, Any?>) {
            val resource = resource()
            val changes = values.toMap()
            val spec = resource.spec()
            resource.additionalProperties["spec"] = if ("maxParallelism" in changes ||
                "workerContainerName" in changes
            ) {
                spec + changes
            } else {
                spec + ("job" to (spec.job() + changes))
            }
            assertFailsWith<IllegalArgumentException> { JobProfile.from(resource) }
        }

        invalid("maxParallelism" to 0)
        invalid("maxParallelism" to 10_001)
        invalid("workerContainerName" to "")
        invalid("ttlSecondsAfterFinished" to -1)
        invalid("activeDeadlineSeconds" to 0)
        invalid("startupFailureGraceSeconds" to -1)
    }

    @Test
    fun `profile job settings have safe defaults`() {
        val resource = resource()
        resource.additionalProperties["spec"] = resource.spec() - "job" - "maxParallelism" -
            "workerContainerName"

        val profile = JobProfile.from(resource)

        assertEquals(1, profile.maxParallelism)
        assertEquals("worker", profile.workerContainerName)
        assertEquals(0, profile.backoffLimit)
        assertEquals(null, profile.ttlSecondsAfterFinished)
        assertEquals(DEFAULT_ACTIVE_DEADLINE_SECONDS, profile.activeDeadlineSeconds)
        assertEquals(DEFAULT_STARTUP_FAILURE_GRACE_SECONDS, profile.startupFailureGraceSeconds)
    }

    @Test
    fun `job identity check requires both durable annotations`() {
        val request = KubernetesJobRequest("training", "model-1")
        val dispatchId = UUID.random()
        fun job(idempotencyKey: String?, id: String?): io.fabric8.kubernetes.api.model.batch.v1.Job {
            val metadata = ObjectMetaBuilder()
            idempotencyKey?.let { metadata.addToAnnotations(IDEMPOTENCY_KEY_ANNOTATION, it) }
            id?.let { metadata.addToAnnotations(DISPATCH_ID_ANNOTATION, it) }
            return io.fabric8.kubernetes.api.model.batch.v1.JobBuilder()
                .withMetadata(metadata.build())
                .build()
        }

        assertTrue(isExpectedJob(job("model-1", dispatchId.toString()), request, dispatchId))
        assertFalse(isExpectedJob(job("other", dispatchId.toString()), request, dispatchId))
        assertFalse(isExpectedJob(job("model-1", UUID.random().toString()), request, dispatchId))
        assertFalse(isExpectedJob(job(null, null), request, dispatchId))
    }

    @Test
    fun `job builder rejects a profile without a pod spec`() {
        val base = JobProfile.from(resource())
        val invalid = base.copy(podTemplate = PodTemplateSpecBuilder().build())

        assertFailsWith<IllegalStateException> {
            buildKubernetesJob(invalid, KubernetesJobRequest("training", "model-1"), UUID.random())
        }
    }

    @Test
    fun `job builder handles initially absent metadata maps and existing worker environment`() {
        val base = JobProfile.from(resource())
        val template = Serialization.clone(base.podTemplate)
        template.metadata.labels = null
        template.metadata.annotations = null
        template.spec.containers.single().env = listOf(
            EnvVarBuilder().withName("PRESERVED").withValue("yes").build(),
            EnvVarBuilder().withName("REPLACED").withValue("old").build(),
        )
        val profile = base.copy(podTemplate = template)
        val job = buildKubernetesJob(
            profile,
            KubernetesJobRequest(
                profile = profile.name,
                idempotencyKey = "environment",
                environment = mapOf("REPLACED" to "new"),
            ),
            UUID.random(),
        )

        val environment = job.spec.template.spec.containers.single().env.associate {
            it.name to it.value
        }
        assertEquals("yes", environment["PRESERVED"])
        assertEquals("new", environment["REPLACED"])
        assertEquals("environment", job.metadata.annotations[IDEMPOTENCY_KEY_ANNOTATION])
    }

    @Test
    fun `job identity check rejects a job without metadata`() {
        assertFalse(
            isExpectedJob(
                io.fabric8.kubernetes.api.model.batch.v1.JobBuilder().build(),
                KubernetesJobRequest("training", "model-1"),
                UUID.random(),
            )
        )
    }

    @Test
    fun `managed label values remain valid and deterministic`() {
        assertEquals("job", kubernetesLabelValue("***"))
        assertEquals("a-b", kubernetesLabelValue(".a b_"))
        assertTrue(kubernetesLabelValue("x".repeat(100)).length <= 63)
        assertTrue(kubernetesJobName("***", "job-1").startsWith("job-"))
    }

    private fun resource(workerContainerName: String = "worker"): GenericKubernetesResource {
        val resources = ResourceRequirementsBuilder()
            .addToLimits("nvidia.com/gpu", Quantity("1"))
            .build()
        val podTemplate = PodTemplateSpecBuilder()
            .withNewMetadata()
            .addToLabels("custom", "preserved")
            .endMetadata()
            .withSpec(
                PodSpecBuilder()
                    .addToNodeSelector("workload", "gpu")
                    .withTolerations(
                        TolerationBuilder()
                            .withKey("dedicated")
                            .withOperator("Equal")
                            .withValue("training")
                            .withEffect("NoSchedule")
                            .build()
                    )
                    .withContainers(
                        ContainerBuilder()
                            .withName("worker")
                            .withImage("example/training:latest")
                            .withResources(resources)
                            .build()
                    )
                    .build()
            )
            .build()
        @Suppress("UNCHECKED_CAST")
        val podTemplateMap = Serialization.jsonMapper().convertValue(
            podTemplate,
            Map::class.java,
        ) as Map<String, Any?>
        return GenericKubernetesResource().also {
            it.apiVersion = API_VERSION
            it.kind = KIND
            it.metadata = ObjectMetaBuilder()
                .withName("training")
                .withNamespace("workers")
                .withUid("profile-uid")
                .withGeneration(3)
                .build()
            it.additionalProperties["spec"] = mapOf(
                "maxParallelism" to 2,
                "workerContainerName" to workerContainerName,
                "job" to mapOf(
                    "backoffLimit" to 0,
                    "ttlSecondsAfterFinished" to 3600,
                    "activeDeadlineSeconds" to 21_600,
                ),
                "podTemplate" to podTemplateMap,
            )
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun GenericKubernetesResource.spec(): Map<String, Any?> =
    additionalProperties["spec"] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.job(): Map<String, Any?> =
    this["job"] as Map<String, Any?>
