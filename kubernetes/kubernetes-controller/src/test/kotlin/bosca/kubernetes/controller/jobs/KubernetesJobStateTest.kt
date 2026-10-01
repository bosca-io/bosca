package bosca.kubernetes.controller.jobs

import bosca.serialization.UUID
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodConditionBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubernetesJobStateTest {

    @Test
    fun `job condition helpers require an affirmative terminal condition`() {
        fun job(type: String?, status: String?) = JobBuilder()
            .withStatus(
                JobStatusBuilder()
                    .withConditions(
                        JobConditionBuilder().withType(type).withStatus(status).build()
                    )
                    .build()
            )
            .build()

        assertTrue(job("Complete", "True").isComplete())
        assertTrue(job("Complete", "True").isTerminal())
        assertTrue(job("Failed", "True").isTerminal())
        assertFalse(job("Failed", "True").isComplete())
        assertFalse(job("Complete", "False").isTerminal())
        assertFalse(job("Complete", null).isTerminal())
        assertFalse(job(null, "True").isTerminal())
        assertFalse(job("Other", "True").isTerminal())
        assertFalse(JobBuilder().build().isTerminal())
    }

    @Test
    fun `job failure message prefers detail then reason then a stable default`() {
        fun failed(message: String?, reason: String?, status: String = "True") = JobBuilder()
            .withStatus(
                JobStatusBuilder()
                    .withConditions(
                        JobConditionBuilder()
                            .withType("Failed")
                            .withStatus(status)
                            .withMessage(message)
                            .withReason(reason)
                            .build()
                    )
                    .build()
            )
            .build()

        assertEquals("pod failed", failed("pod failed", "BackoffLimitExceeded").failureMessage())
        assertEquals("BackoffLimitExceeded", failed(" ", "BackoffLimitExceeded").failureMessage())
        assertEquals("BackoffLimitExceeded", failed(null, "BackoffLimitExceeded").failureMessage())
        assertEquals("Kubernetes Job failed", failed("", "").failureMessage())
        assertEquals("Kubernetes Job failed", failed(null, null).failureMessage())
        assertNull(failed("ignored", "ignored", status = "False").failureMessage())
        assertNull(JobBuilder().build().failureMessage())
    }

    @Test
    fun `dispatch annotation parser rejects absent and malformed identifiers`() {
        val dispatchId = UUID.random()
        fun job(value: String?): io.fabric8.kubernetes.api.model.batch.v1.Job {
            val metadata = ObjectMetaBuilder()
            value?.let { metadata.addToAnnotations(DISPATCH_ID_ANNOTATION, it) }
            return JobBuilder().withMetadata(metadata.build()).build()
        }

        assertEquals(dispatchId, job(dispatchId.toString()).dispatchIdOrNull())
        assertNull(job("not-a-uuid").dispatchIdOrNull())
        assertNull(job(null).dispatchIdOrNull())
        assertNull(JobBuilder().build().dispatchIdOrNull())
    }

    @Test
    fun `unschedulable pod is not a startup failure`() {
        val pod = PodBuilder()
            .withMetadata(ObjectMetaBuilder().withName("trainer-1").build())
            .withStatus(
                PodStatusBuilder()
                    .withConditions(
                        PodConditionBuilder()
                            .withType("PodScheduled")
                            .withStatus("False")
                            .withReason("Unschedulable")
                            .withMessage("no matching GPU node")
                            .build()
                    )
                    .build()
            )
            .build()

        assertNull(pod.startupFailureMessage())

        listOf(
            PodConditionBuilder()
                .withType("Ready")
                .withStatus("False")
                .withReason("Unschedulable")
                .build(),
            PodConditionBuilder()
                .withType("PodScheduled")
                .withStatus("True")
                .withReason("Unschedulable")
                .build(),
            PodConditionBuilder()
                .withType("PodScheduled")
                .withStatus("False")
                .withReason("Other")
                .build(),
        ).forEach { condition ->
            val pod = PodBuilder()
                .withStatus(PodStatusBuilder().withConditions(condition).build())
                .build()
            assertNull(pod.startupFailureMessage())
        }
    }

    @Test
    fun `container startup failures include the best available detail`() {
        fun pod(reason: String?, message: String?, init: Boolean = false) = PodBuilder()
            .withMetadata(ObjectMetaBuilder().withName("worker-1").build())
            .withStatus(
                PodStatusBuilder().also { status ->
                    val container = ContainerStatusBuilder()
                        .withName("worker")
                        .withState(
                            ContainerStateBuilder()
                                .withWaiting(
                                    ContainerStateWaitingBuilder()
                                        .withReason(reason)
                                        .withMessage(message)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                    if (init) status.withInitContainerStatuses(container)
                    else status.withContainerStatuses(container)
                }.build()
            )
            .build()

        assertEquals(
            "Pod worker-1 failed to start: ImagePullBackOff — manifest unknown",
            pod("ImagePullBackOff", "manifest unknown").startupFailureMessage(),
        )
        assertEquals(
            "Pod worker-1 failed to start: CreateContainerError",
            pod("CreateContainerError", " ", init = true).startupFailureMessage(),
        )
        assertEquals(
            "Pod worker-1 failed to start: ErrImagePull",
            pod("ErrImagePull", null).startupFailureMessage(),
        )
        val unnamed = PodBuilder(pod("InvalidImageName", null))
            .withMetadata(null)
            .build()
        assertEquals(
            "Pod unknown pod failed to start: InvalidImageName",
            unnamed.startupFailureMessage(),
        )
        val noWaitingState = PodBuilder()
            .withStatus(
                PodStatusBuilder()
                    .withContainerStatuses(
                        ContainerStatusBuilder()
                            .withName("worker")
                            .withState(ContainerStateBuilder().build())
                            .build(),
                        ContainerStatusBuilder().withName("sidecar").withState(null).build(),
                    )
                    .build()
            )
            .build()
        assertNull(noWaitingState.startupFailureMessage())
        assertNull(pod("ContainerCreating", "waiting").startupFailureMessage())
        assertNull(PodBuilder().build().startupFailureMessage())
    }

    @Test
    fun `container runtime failures include termination detail and ignore successful sidecars`() {
        val failed = ContainerStatusBuilder()
            .withName("worker")
            .withState(
                ContainerStateBuilder()
                    .withTerminated(
                        ContainerStateTerminatedBuilder()
                            .withExitCode(1)
                            .withReason("Error")
                            .withMessage("target job was cancelled")
                            .build()
                    )
                    .build()
            )
            .build()
        val completed = ContainerStatusBuilder()
            .withName("docker")
            .withState(
                ContainerStateBuilder()
                    .withTerminated(
                        ContainerStateTerminatedBuilder()
                            .withExitCode(0)
                            .withReason("Completed")
                            .build()
                    )
                    .build()
            )
            .build()
        val pod = PodBuilder()
            .withMetadata(ObjectMetaBuilder().withName("linux-1").build())
            .withStatus(
                PodStatusBuilder()
                    .withContainerStatuses(failed)
                    .withInitContainerStatuses(completed)
                    .build()
            )
            .build()

        assertEquals(
            "Pod linux-1 container worker failed: Error (exit code 1) — target job was cancelled",
            pod.runtimeFailureMessage(),
        )
        assertNull(
            PodBuilder()
                .withStatus(PodStatusBuilder().withContainerStatuses(completed).build())
                .build()
                .runtimeFailureMessage()
        )
        assertNull(PodBuilder().build().runtimeFailureMessage())
    }
}
