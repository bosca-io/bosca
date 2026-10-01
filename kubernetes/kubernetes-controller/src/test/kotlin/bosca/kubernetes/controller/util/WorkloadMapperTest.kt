package bosca.kubernetes.controller.util

import bosca.kubernetes.model.WorkloadKind
import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins the per-kind workload mappers. The decisions worth fixing in tests:
 *
 *   * Status ladder with pod visibility: `desired==0 → OK`, all ready → OK, any failing
 *     pod → ERROR (nothing ready) / WARN (partially serving), any pending pod → PENDING,
 *     zero credited pods → ERROR, else WARN. Without pod visibility (`podStates == null`)
 *     the coarse ladder applies: `ready==0 → ERROR`, `ready < desired → WARN`. The
 *     studio's status badge wires its colour and label off this — a pod that is merely
 *     Pending must never render the workload as Failing.
 *   * Job has its own status logic — succeeded≥desired → OK, active>0 → WARN, else ERROR.
 *   * CronJob has no "ready" notion — `ready` is active count, `desired` is 1, and the
 *     status flips to WARN only when an execution is in flight.
 *   * `primaryImage` is the first container's image; absent template → empty string.
 *   * `strategy` defaults differ per kind. Deployment/StatefulSet/DaemonSet default to
 *     `RollingUpdate`; ReplicaSet is hard-coded to `RollingUpdate`; Job is `OnFailure`;
 *     CronJob renders `Schedule(<cron>)`.
 *   * `id` falls back to `<Kind>/<ns>/<name>` when uid is missing — `Kind` matches the
 *     fabric8 type, not the WorkloadKind enum string.
 */
class WorkloadMapperTest {

    private fun podTemplateWithImage(image: String) = PodTemplateSpecBuilder()
        .withNewSpec()
            .addToContainers(ContainerBuilder().withName("c").withImage(image).build())
        .endSpec()
        .build()

    // ===== Deployment =====

    @Test
    fun `deployment with all replicas ready maps to OK`() {
        val d = DeploymentBuilder()
            .withNewMetadata()
                .withUid("u")
                .withName("api").withNamespace("prod")
                .addToLabels("app", "api")
            .endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withTemplate(podTemplateWithImage("api:1.2.3"))
                .withNewStrategy().withType("Recreate").endStrategy()
            .endSpec()
            .withNewStatus().withReadyReplicas(3).endStatus()
            .build()
        val w = d.toWorkload()
        assertEquals("u", w.id)
        assertEquals(WorkloadKind.DEPLOYMENT, w.kind)
        assertEquals("api", w.name)
        assertEquals("prod", w.namespace)
        assertEquals(3, w.ready)
        assertEquals(3, w.desired)
        assertEquals(WorkloadStatus.OK, w.status)
        assertEquals("api:1.2.3", w.image)
        assertEquals("Recreate", w.strategy)
        val labels = w.labels
        assertIs<JsonObject>(labels)
        assertEquals("api", labels["app"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deployment with partial readiness maps to WARN`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(3).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(1).endStatus()
            .build()
        assertEquals(WorkloadStatus.WARN, d.toWorkload().status)
    }

    @Test
    fun `deployment with zero ready and no pod visibility falls back to ERROR`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(3).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(0).endStatus()
            .build()
        assertEquals(WorkloadStatus.ERROR, d.toWorkload().status)
    }

    @Test
    fun `deployment with zero ready and pending pods maps to PENDING not ERROR`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(0).endStatus()
            .build()
        val aggregates = WorkloadAggregates(podStates = WorkloadPodStates(total = 1, pending = 1))
        assertEquals(WorkloadStatus.PENDING, d.toWorkload(aggregates).status)
    }

    @Test
    fun `deployment with zero ready and failing pods maps to ERROR`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(2).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(0).endStatus()
            .build()
        val aggregates = WorkloadAggregates(podStates = WorkloadPodStates(total = 2, pending = 1, failing = 1))
        assertEquals(WorkloadStatus.ERROR, d.toWorkload(aggregates).status, "failure evidence outranks pending pods")
    }

    @Test
    fun `deployment partially serving with a failing pod maps to WARN`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(3).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(2).endStatus()
            .build()
        val aggregates = WorkloadAggregates(podStates = WorkloadPodStates(total = 3, failing = 1))
        assertEquals(WorkloadStatus.WARN, d.toWorkload(aggregates).status)
    }

    @Test
    fun `deployment mid-rollout with a pending pod maps to PENDING`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(3).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(2).endStatus()
            .build()
        val aggregates = WorkloadAggregates(podStates = WorkloadPodStates(total = 3, pending = 1))
        assertEquals(WorkloadStatus.PENDING, d.toWorkload(aggregates).status)
    }

    @Test
    fun `deployment with zero credited pods maps to ERROR`() {
        // Desired replicas but the controller produced no pods at all
        // (quota / admission webhook) — nothing is progressing.
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(0).endStatus()
            .build()
        val aggregates = WorkloadAggregates(podStates = WorkloadPodStates())
        assertEquals(WorkloadStatus.ERROR, d.toWorkload(aggregates).status)
    }

    @Test
    fun `deployment with running-but-unready pods maps to WARN not ERROR`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().withReadyReplicas(0).endStatus()
            .build()
        val aggregates = WorkloadAggregates(podStates = WorkloadPodStates(total = 1))
        assertEquals(WorkloadStatus.WARN, d.toWorkload(aggregates).status, "readiness-probe warmup must not flash red")
    }

    @Test
    fun `deployment scaled to zero is OK not ERROR`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(0).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .withNewStatus().endStatus()
            .build()
        assertEquals(WorkloadStatus.OK, d.toWorkload().status)
    }

    @Test
    fun `deployment id falls back to Kind slash ns slash name when uid missing`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .build()
        assertEquals("Deployment/prod/api", d.toWorkload().id)
    }

    @Test
    fun `deployment defaults strategy to RollingUpdate when unspecified`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .build()
        assertEquals("RollingUpdate", d.toWorkload().strategy)
    }

    @Test
    fun `deployment labels null when metadata labels empty`() {
        val d = DeploymentBuilder()
            .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("api:1")).endSpec()
            .build()
        assertNull(d.toWorkload().labels)
    }

    // ===== StatefulSet =====

    @Test
    fun `statefulset maps with updateStrategy as strategy`() {
        val s = StatefulSetBuilder()
            .withNewMetadata().withName("db").withNamespace("data").endMetadata()
            .withNewSpec()
                .withReplicas(3)
                .withTemplate(podTemplateWithImage("postgres:16"))
                .withNewUpdateStrategy().withType("OnDelete").endUpdateStrategy()
            .endSpec()
            .withNewStatus().withReadyReplicas(2).endStatus()
            .build()
        val w = s.toWorkload()
        assertEquals(WorkloadKind.STATEFUL_SET, w.kind)
        assertEquals("OnDelete", w.strategy)
        assertEquals(WorkloadStatus.WARN, w.status)
        assertEquals("postgres:16", w.image)
    }

    @Test
    fun `statefulset id falls back to StatefulSet slash ns slash name`() {
        val s = StatefulSetBuilder()
            .withNewMetadata().withName("db").withNamespace("data").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("p:1")).endSpec()
            .build()
        assertEquals("StatefulSet/data/db", s.toWorkload().id)
    }

    @Test
    fun `statefulset default strategy is RollingUpdate`() {
        val s = StatefulSetBuilder()
            .withNewMetadata().withName("db").withNamespace("data").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("p:1")).endSpec()
            .build()
        assertEquals("RollingUpdate", s.toWorkload().strategy)
    }

    // ===== DaemonSet =====

    @Test
    fun `daemonset reads desired and ready from status counters`() {
        val d = DaemonSetBuilder()
            .withNewMetadata().withName("agent").withNamespace("kube-system").endMetadata()
            .withNewSpec()
                .withTemplate(podTemplateWithImage("agent:1"))
                .withNewUpdateStrategy().withType("OnDelete").endUpdateStrategy()
            .endSpec()
            .withNewStatus().withDesiredNumberScheduled(5).withNumberReady(5).endStatus()
            .build()
        val w = d.toWorkload()
        assertEquals(WorkloadKind.DAEMON_SET, w.kind)
        assertEquals("OnDelete", w.strategy)
        assertEquals(5, w.ready)
        assertEquals(5, w.desired)
        assertEquals(WorkloadStatus.OK, w.status)
    }

    @Test
    fun `daemonset id falls back when uid missing`() {
        val d = DaemonSetBuilder()
            .withNewMetadata().withName("agent").withNamespace("kube-system").endMetadata()
            .withNewSpec().withTemplate(podTemplateWithImage("a:1")).endSpec()
            .build()
        assertEquals("DaemonSet/kube-system/agent", d.toWorkload().id)
        assertEquals("RollingUpdate", d.toWorkload().strategy)
    }

    // ===== ReplicaSet =====

    @Test
    fun `replicaset reads replica counters and is always RollingUpdate strategy`() {
        val r = ReplicaSetBuilder()
            .withNewMetadata().withName("rs").withNamespace("ns").endMetadata()
            .withNewSpec().withReplicas(2).withTemplate(podTemplateWithImage("x:1")).endSpec()
            .withNewStatus().withReadyReplicas(2).endStatus()
            .build()
        val w = r.toWorkload()
        assertEquals(WorkloadKind.REPLICA_SET, w.kind)
        assertEquals("RollingUpdate", w.strategy)
        assertEquals(WorkloadStatus.OK, w.status)
    }

    @Test
    fun `replicaset id falls back when uid missing`() {
        val r = ReplicaSetBuilder()
            .withNewMetadata().withName("rs").withNamespace("ns").endMetadata()
            .withNewSpec().withReplicas(1).withTemplate(podTemplateWithImage("x:1")).endSpec()
            .build()
        assertEquals("ReplicaSet/ns/rs", r.toWorkload().id)
    }

    // ===== Job =====

    @Test
    fun `job with succeeded greater or equal to completions is OK`() {
        val j = JobBuilder()
            .withNewMetadata().withName("backup").withNamespace("data").endMetadata()
            .withNewSpec().withCompletions(1).withTemplate(podTemplateWithImage("backup:1")).endSpec()
            .withNewStatus().withSucceeded(1).endStatus()
            .build()
        val w = j.toWorkload()
        assertEquals(WorkloadKind.JOB, w.kind)
        assertEquals("OnFailure", w.strategy)
        assertEquals(1, w.ready)
        assertEquals(1, w.desired)
        assertEquals(WorkloadStatus.OK, w.status)
    }

    @Test
    fun `job with active pods but no success yet is WARN`() {
        val j = JobBuilder()
            .withNewMetadata().withName("backup").withNamespace("data").endMetadata()
            .withNewSpec().withCompletions(1).withTemplate(podTemplateWithImage("b:1")).endSpec()
            .withNewStatus().withActive(1).withSucceeded(0).endStatus()
            .build()
        assertEquals(WorkloadStatus.WARN, j.toWorkload().status)
    }

    @Test
    fun `job with no active no succeeded is ERROR`() {
        val j = JobBuilder()
            .withNewMetadata().withName("backup").withNamespace("data").endMetadata()
            .withNewSpec().withCompletions(1).withTemplate(podTemplateWithImage("b:1")).endSpec()
            .withNewStatus().withActive(0).withSucceeded(0).endStatus()
            .build()
        assertEquals(WorkloadStatus.ERROR, j.toWorkload().status)
    }

    @Test
    fun `job defaults completions to 1 when unspecified`() {
        val j = JobBuilder()
            .withNewMetadata().withName("backup").withNamespace("data").endMetadata()
            .withNewSpec().withTemplate(podTemplateWithImage("b:1")).endSpec()
            .build()
        assertEquals(1, j.toWorkload().desired)
    }

    @Test
    fun `job id falls back when uid missing`() {
        val j = JobBuilder()
            .withNewMetadata().withName("backup").withNamespace("data").endMetadata()
            .withNewSpec().withTemplate(podTemplateWithImage("b:1")).endSpec()
            .build()
        assertEquals("Job/data/backup", j.toWorkload().id)
    }

    // ===== CronJob =====

    @Test
    fun `cronjob with no active executions is OK and ready is 0`() {
        val c = CronJobBuilder()
            .withNewMetadata().withName("nightly").withNamespace("data").endMetadata()
            .withNewSpec()
                .withSchedule("0 2 * * *")
                .withNewJobTemplate().withNewSpec().withTemplate(podTemplateWithImage("backup:1")).endSpec().endJobTemplate()
            .endSpec()
            .build()
        val w = c.toWorkload()
        assertEquals(WorkloadKind.CRON_JOB, w.kind)
        assertEquals(0, w.ready)
        assertEquals(1, w.desired)
        assertEquals(WorkloadStatus.OK, w.status)
        assertEquals("Schedule(0 2 * * *)", w.strategy)
        assertEquals("backup:1", w.image)
    }

    @Test
    fun `cronjob with an active execution flips to WARN`() {
        val c = CronJobBuilder()
            .withNewMetadata().withName("nightly").withNamespace("data").endMetadata()
            .withNewSpec()
                .withSchedule("@hourly")
                .withNewJobTemplate().withNewSpec().withTemplate(podTemplateWithImage("b:1")).endSpec().endJobTemplate()
            .endSpec()
            .withNewStatus()
                .addToActive(ObjectReferenceBuilder().withName("nightly-1234").build())
            .endStatus()
            .build()
        val w = c.toWorkload()
        assertEquals(WorkloadStatus.WARN, w.status)
        assertEquals(1, w.ready, "ready reports active execution count for cronjobs")
        assertEquals("Schedule(@hourly)", w.strategy)
    }

    @Test
    fun `cronjob renders Schedule with question mark when schedule is null`() {
        val c = CronJobBuilder()
            .withNewMetadata().withName("nightly").withNamespace("data").endMetadata()
            .withNewSpec()
                .withNewJobTemplate().withNewSpec().withTemplate(podTemplateWithImage("b:1")).endSpec().endJobTemplate()
            .endSpec()
            .build()
        assertEquals("Schedule(?)", c.toWorkload().strategy)
    }

    @Test
    fun `cronjob id falls back when uid missing`() {
        val c = CronJobBuilder()
            .withNewMetadata().withName("nightly").withNamespace("data").endMetadata()
            .withNewSpec()
                .withSchedule("@daily")
                .withNewJobTemplate().withNewSpec().withTemplate(podTemplateWithImage("b:1")).endSpec().endJobTemplate()
            .endSpec()
            .build()
        assertEquals("CronJob/data/nightly", c.toWorkload().id)
    }

    @Test
    fun `cronjob primary image falls back to empty when no template`() {
        val c = CronJobBuilder()
            .withNewMetadata().withName("nightly").withNamespace("data").endMetadata()
            .withNewSpec().withSchedule("@daily").endSpec()
            .build()
        assertEquals("", c.toWorkload().image)
    }
}
