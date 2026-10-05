package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStateWaitingBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the wire shape produced by [io.fabric8.kubernetes.api.model.Pod.toPodWire]. The
 * mapping decisions worth pinning are:
 *
 *   * `ready` is `<readyCount>/<totalCount>` using `coerceAtLeast` so a
 *     pod with no statuses yet renders `0/N` (N = container spec count),
 *     not `0/0`.
 *   * `status` prefers a stuck container's waiting reason
 *     (`CrashLoopBackOff`, `ImagePullBackOff`, …) over the kubelet phase
 *     so the studio's `PodStatusBadge` colours the worst offenders.
 *   * `id` falls back to `<namespace>/<name>` when uid is missing — keeps
 *     list keys deterministic for newly-observed pods before fabric8
 *     fills uid in.
 *   * `workloadId` is the first owner-reference uid; null when no owner.
 */
class PodMapperTest {

    @Test
    fun `running pod with all containers ready maps to phase Running and ready N over N`() {
        val pod = PodBuilder()
            .withNewMetadata()
                .withUid("pod-uid-1")
                .withName("api-7df9")
                .withNamespace("prod")
                .withCreationTimestamp("2026-05-14T12:00:00Z")
            .endMetadata()
            .withNewSpec()
                .withNodeName("node-1")
                .addToContainers(ContainerBuilder().withName("api").withImage("api:1").build())
                .addToContainers(ContainerBuilder().withName("sidecar").withImage("envoy:1").build())
            .endSpec()
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Running")
                    .withPodIP("10.0.0.5")
                    .withHostIP("10.10.0.1")
                    .addToContainerStatuses(
                        ContainerStatusBuilder()
                            .withName("api").withReady(true).withRestartCount(0)
                            .withImage("api:1").withImageID("api@sha256:abc")
                            .build()
                    )
                    .addToContainerStatuses(
                        ContainerStatusBuilder()
                            .withName("sidecar").withReady(true).withRestartCount(2)
                            .withImage("envoy:1").withImageID("envoy@sha256:def")
                            .build()
                    )
                    .build()
            )
            .build()

        val wire = pod.toPodWire()

        assertEquals("pod-uid-1", wire.id)
        assertEquals("api-7df9", wire.name)
        assertEquals("prod", wire.namespace)
        assertEquals("node-1", wire.node)
        assertEquals("Running", wire.status)
        assertEquals("2/2", wire.ready)
        assertEquals(2, wire.restarts)
        assertEquals("10.0.0.5", wire.podIP)
        assertEquals("10.10.0.1", wire.hostIP)
    }

    @Test
    fun `pod with crash-looping container surfaces the waiting reason instead of Running phase`() {
        val pod = PodBuilder()
            .withNewMetadata().withUid("u").withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .addToContainers(ContainerBuilder().withName("c").build())
            .endSpec()
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Running")
                    .addToContainerStatuses(
                        ContainerStatusBuilder()
                            .withName("c").withReady(false).withRestartCount(5)
                            .withState(
                                ContainerStateBuilder().withWaiting(
                                    ContainerStateWaitingBuilder().withReason("CrashLoopBackOff").build()
                                ).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        assertEquals("CrashLoopBackOff", pod.toPodWire().status)
    }

    @Test
    fun `pod stuck on ImagePullBackOff also wins over the phase`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .addToContainers(ContainerBuilder().withName("c").build())
            .endSpec()
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Pending")
                    .addToContainerStatuses(
                        ContainerStatusBuilder().withName("c").withReady(false).withRestartCount(0)
                            .withState(
                                ContainerStateBuilder().withWaiting(
                                    ContainerStateWaitingBuilder().withReason("ImagePullBackOff").build()
                                ).build()
                            ).build()
                    ).build()
            ).build()

        assertEquals("ImagePullBackOff", pod.toPodWire().status)
    }

    @Test
    fun `pod with a non-stuck waiting reason falls through to the kubelet phase`() {
        // ContainerCreating is in the stuck set per current STUCK_REASONS;
        // pick a reason that's *not* in the set to exercise the fall-through.
        val pod = PodBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .addToContainers(ContainerBuilder().withName("c").build())
            .endSpec()
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Pending")
                    .addToContainerStatuses(
                        ContainerStatusBuilder().withName("c").withReady(false).withRestartCount(0)
                            .withState(
                                ContainerStateBuilder().withWaiting(
                                    ContainerStateWaitingBuilder().withReason("PodInitializing").build()
                                ).build()
                            ).build()
                    ).build()
            ).build()

        assertEquals("Pending", pod.toPodWire().status)
    }

    @Test
    fun `pod with neither phase nor statuses renders Unknown and 0 over container-count`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec()
                .addToContainers(ContainerBuilder().withName("a").build())
                .addToContainers(ContainerBuilder().withName("b").build())
                .addToContainers(ContainerBuilder().withName("c").build())
            .endSpec()
            .build()

        val wire = pod.toPodWire()
        assertEquals("Unknown", wire.status)
        assertEquals("0/3", wire.ready)
        assertEquals(0, wire.restarts)
    }

    @Test
    fun `pod id falls back to namespace and name when uid is missing`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec().addToContainers(ContainerBuilder().withName("c").build()).endSpec()
            .build()

        assertEquals("ns/p", pod.toPodWire().id)
    }

    @Test
    fun `workloadId is the first owner-reference uid`() {
        val pod = PodBuilder()
            .withNewMetadata()
                .withName("p").withNamespace("ns")
                .withOwnerReferences(
                    OwnerReferenceBuilder().withKind("ReplicaSet").withName("rs").withUid("rs-uid").build(),
                    OwnerReferenceBuilder().withKind("Other").withName("o").withUid("other-uid").build(),
                )
            .endMetadata()
            .withNewSpec().addToContainers(ContainerBuilder().withName("c").build()).endSpec()
            .build()

        assertEquals("rs-uid", pod.toPodWire().workloadId)
    }

    @Test
    fun `workloadId is null when there are no owner references`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec().addToContainers(ContainerBuilder().withName("c").build()).endSpec()
            .build()

        assertNull(pod.toPodWire().workloadId)
    }

    @Test
    fun `ready count uses the larger of container spec size and statuses size`() {
        // Spec has 1 container, statuses claims 3 — kubelet has just told
        // us about extra containers we haven't seen in spec yet (e.g.,
        // sidecar injection race). Total should be 3, not 1.
        val pod = PodBuilder()
            .withNewMetadata().withName("p").withNamespace("ns").endMetadata()
            .withNewSpec().addToContainers(ContainerBuilder().withName("c").build()).endSpec()
            .withStatus(
                PodStatusBuilder()
                    .withPhase("Running")
                    .addToContainerStatuses(
                        ContainerStatusBuilder().withName("a").withReady(true).withRestartCount(0).build(),
                        ContainerStatusBuilder().withName("b").withReady(true).withRestartCount(0).build(),
                        ContainerStatusBuilder().withName("c").withReady(false).withRestartCount(0).build(),
                    )
                    .build()
            ).build()

        assertEquals("2/3", pod.toPodWire().ready)
    }

    @Test
    fun `cpu and memory placeholders are zero until metrics-server is wired`() {
        val pod = PodBuilder().withNewMetadata().withName("p").withNamespace("ns").endMetadata().build()
        val wire = pod.toPodWire()
        assertEquals(0, wire.cpu)
        assertEquals(0, wire.memory)
        assertEquals("", wire.node)
        assertNotNull(wire.age, "age string must be present even when timestamp is null")
        assertTrue(wire.age == "-" || wire.age.isNotBlank())
    }
}
