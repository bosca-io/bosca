package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the pod-state classification the workload status ladder reads:
 *
 *   * Phase `Pending` (scheduling, image pulling, container creating) is *pending*,
 *     not failing — the whole point of the PENDING workload status.
 *   * A container waiting in a known-bad reason (CrashLoopBackOff, ImagePullBackOff,
 *     ErrImagePull, config errors, InvalidImageName) is *failing* even while the pod
 *     phase still says Pending or Running; init containers count too.
 *   * Phase `Failed` is failing.
 *   * Running-but-unready pods land in neither bucket.
 *   * Terminating pods (deletionTimestamp set) are invisible to the summary.
 *
 * `buildPodStateIndex` mirrors the aggregates ownership walk: direct owner, plus
 * ReplicaSet→Deployment and Job→CronJob parents.
 */
class WorkloadPodStatesTest {

    private fun waiting(name: String, reason: String) = ContainerStatusBuilder()
        .withName(name)
        .withNewState().withNewWaiting().withReason(reason).endWaiting().endState()
        .build()

    private fun pod(
        phase: String,
        waitingReason: String? = null,
        initWaitingReason: String? = null,
        deleted: Boolean = false,
        ownerUid: String? = null,
    ): Pod {
        val meta = ObjectMetaBuilder().withName("p").withNamespace("ns")
        if (deleted) meta.withDeletionTimestamp("2026-07-19T00:00:00Z")
        if (ownerUid != null) {
            meta.addToOwnerReferences(OwnerReferenceBuilder().withKind("ReplicaSet").withUid(ownerUid).build())
        }
        val status = PodStatusBuilder().withPhase(phase)
        if (waitingReason != null) status.addToContainerStatuses(waiting("c", waitingReason))
        if (initWaitingReason != null) status.addToInitContainerStatuses(waiting("init", initWaitingReason))
        return PodBuilder().withMetadata(meta.build()).withStatus(status.build()).build()
    }

    // ===== podStatesOf =====

    @Test
    fun `pending phase counts as pending not failing`() {
        val states = podStatesOf(listOf(pod(phase = "Pending")))
        assertEquals(WorkloadPodStates(total = 1, pending = 1, failing = 0), states)
    }

    @Test
    fun `container creating stays pending`() {
        val states = podStatesOf(listOf(pod(phase = "Pending", waitingReason = "ContainerCreating")))
        assertEquals(WorkloadPodStates(total = 1, pending = 1, failing = 0), states)
    }

    @Test
    fun `crashloopbackoff counts as failing even in Running phase`() {
        val states = podStatesOf(listOf(pod(phase = "Running", waitingReason = "CrashLoopBackOff")))
        assertEquals(WorkloadPodStates(total = 1, pending = 0, failing = 1), states)
    }

    @Test
    fun `imagepullbackoff in Pending phase counts as failing not pending`() {
        val states = podStatesOf(listOf(pod(phase = "Pending", waitingReason = "ImagePullBackOff")))
        assertEquals(WorkloadPodStates(total = 1, pending = 0, failing = 1), states)
    }

    @Test
    fun `init container crashloop counts as failing`() {
        val states = podStatesOf(listOf(pod(phase = "Pending", initWaitingReason = "CrashLoopBackOff")))
        assertEquals(WorkloadPodStates(total = 1, pending = 0, failing = 1), states)
    }

    @Test
    fun `failed phase counts as failing`() {
        val states = podStatesOf(listOf(pod(phase = "Failed")))
        assertEquals(WorkloadPodStates(total = 1, pending = 0, failing = 1), states)
    }

    @Test
    fun `running unready pod lands in neither bucket`() {
        val states = podStatesOf(listOf(pod(phase = "Running")))
        assertEquals(WorkloadPodStates(total = 1, pending = 0, failing = 0), states)
    }

    @Test
    fun `terminating pods are excluded entirely`() {
        val states = podStatesOf(listOf(pod(phase = "Pending", deleted = true)))
        assertEquals(WorkloadPodStates(total = 0, pending = 0, failing = 0), states)
    }

    @Test
    fun `mixed pods bucket independently`() {
        val states = podStatesOf(
            listOf(
                pod(phase = "Running"),
                pod(phase = "Pending"),
                pod(phase = "Running", waitingReason = "CrashLoopBackOff"),
                pod(phase = "Running", deleted = true),
            ),
        )
        assertEquals(WorkloadPodStates(total = 3, pending = 1, failing = 1), states)
    }

    // ===== buildPodStateIndex =====

    @Test
    fun `pod credits its direct owner and the deployment above its replicaset`() {
        val rs = ReplicaSetBuilder()
            .withNewMetadata()
                .withUid("rs-uid")
                .addToOwnerReferences(OwnerReferenceBuilder().withKind("Deployment").withUid("dep-uid").build())
            .endMetadata()
            .build()
        val index = buildPodStateIndex(
            pods = listOf(pod(phase = "Pending", ownerUid = "rs-uid")),
            replicaSets = listOf(rs),
            jobs = emptyList(),
        )
        assertEquals(WorkloadPodStates(total = 1, pending = 1), index["rs-uid"])
        assertEquals(WorkloadPodStates(total = 1, pending = 1), index["dep-uid"])
    }

    @Test
    fun `pod credits the cronjob above its job`() {
        val job = JobBuilder()
            .withNewMetadata()
                .withUid("job-uid")
                .addToOwnerReferences(OwnerReferenceBuilder().withKind("CronJob").withUid("cj-uid").build())
            .endMetadata()
            .build()
        val index = buildPodStateIndex(
            pods = listOf(pod(phase = "Failed", ownerUid = "job-uid")),
            replicaSets = emptyList(),
            jobs = listOf(job),
        )
        assertEquals(WorkloadPodStates(total = 1, failing = 1), index["job-uid"])
        assertEquals(WorkloadPodStates(total = 1, failing = 1), index["cj-uid"])
    }

    @Test
    fun `ownerless pods are not indexed`() {
        val index = buildPodStateIndex(
            pods = listOf(pod(phase = "Pending")),
            replicaSets = emptyList(),
            jobs = emptyList(),
        )
        assertEquals(emptyMap(), index)
        assertNull(index["anything"])
    }
}
