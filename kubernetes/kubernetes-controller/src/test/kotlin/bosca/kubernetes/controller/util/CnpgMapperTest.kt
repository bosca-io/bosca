package bosca.kubernetes.controller.util

import bosca.kubernetes.model.WorkloadStatus
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerStateBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the CNPG cluster mapper. The decisions worth fixing in tests:
 *
 *   * Phase → `WorkloadStatus` ladder pinned per known string —
 *     `Cluster in healthy state` and `Cluster in healthy …` → OK,
 *     `Failover` / `Switchover` / `Setting up` / `Upgrading` → WARN,
 *     `Failed` / `Unhealthy` substrings → ERROR, blank → WARN (status
 *     not yet observed), anything else → OK.
 *   * `postgresVersion` parses the image tag: `…:16.4-bookworm` → `16.4`;
 *     `internal/pg:custom` → empty (tag doesn't start with a digit).
 *   * `backupSchedule` and `lastBackup` are pulled from the
 *     route-supplied lookup maps keyed by `<ns>/<name>`.
 */
class CnpgMapperTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-15T12:00:00Z")

    private fun cluster(
        spec: Map<String, Any?>? = null,
        status: Map<String, Any?>? = null,
        name: String = "pg",
        namespace: String = "data",
    ): GenericKubernetesResource {
        val r = GenericKubernetesResourceBuilder().build()
        r.metadata = ObjectMetaBuilder().withName(name).withNamespace(namespace).build()
        r.kind = "Cluster"
        r.apiVersion = "postgresql.cnpg.io/v1"
        if (spec != null) r.setAdditionalProperty("spec", spec)
        if (status != null) r.setAdditionalProperty("status", status)
        return r
    }

    // ===== Phase → status =====

    @Test
    fun `phase Cluster in healthy state maps to OK`() {
        val c = cluster(status = mapOf("phase" to "Cluster in healthy state"))
        val w = c.toCnpgCluster(emptyMap(), emptyMap(), now)
        assertEquals(WorkloadStatus.OK, w.statusKind)
        assertEquals("Cluster in healthy state", w.status)
    }

    @Test
    fun `phase Cluster in healthy with extra suffix is OK`() {
        val c = cluster(status = mapOf("phase" to "Cluster in healthy state with replication catching up"))
        assertEquals(WorkloadStatus.OK, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    @Test
    fun `phase containing Failover or Switchover is WARN`() {
        for (phase in listOf("Failover in progress", "Switchover in progress")) {
            val c = cluster(status = mapOf("phase" to phase))
            assertEquals(WorkloadStatus.WARN, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind,
                "phase $phase should map to WARN")
        }
    }

    @Test
    fun `phase Setting up primary is WARN`() {
        val c = cluster(status = mapOf("phase" to "Setting up primary"))
        assertEquals(WorkloadStatus.WARN, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    @Test
    fun `phase Upgrading is WARN`() {
        val c = cluster(status = mapOf("phase" to "Upgrading PostgreSQL"))
        assertEquals(WorkloadStatus.WARN, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    @Test
    fun `phase containing Failed is ERROR`() {
        val c = cluster(status = mapOf("phase" to "Cluster has failed to start"))
        assertEquals(WorkloadStatus.ERROR, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    @Test
    fun `phase containing Unhealthy is ERROR`() {
        val c = cluster(status = mapOf("phase" to "Unhealthy replica"))
        assertEquals(WorkloadStatus.ERROR, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    @Test
    fun `blank phase is WARN status-not-yet-observed`() {
        val c = cluster(status = mapOf("phase" to ""))
        assertEquals(WorkloadStatus.WARN, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    @Test
    fun `null status maps to WARN with blank status string`() {
        val c = cluster()
        val w = c.toCnpgCluster(emptyMap(), emptyMap(), now)
        assertEquals(WorkloadStatus.WARN, w.statusKind)
        assertEquals("", w.status)
    }

    @Test
    fun `unknown phase falls through to OK`() {
        val c = cluster(status = mapOf("phase" to "Inspecting database"))
        assertEquals(WorkloadStatus.OK, c.toCnpgCluster(emptyMap(), emptyMap(), now).statusKind)
    }

    // ===== Postgres version parsing =====

    @Test
    fun `postgresVersion parses major-minor from a vanilla image tag`() {
        val c = cluster(spec = mapOf("imageName" to "ghcr.io/cloudnative-pg/postgresql:16.4"))
        assertEquals("16.4", c.toCnpgCluster(emptyMap(), emptyMap(), now).postgresVersion)
    }

    @Test
    fun `postgresVersion strips dash-suffix variants like bookworm`() {
        val c = cluster(spec = mapOf("imageName" to "registry.io/postgres:16.4-bookworm"))
        assertEquals("16.4", c.toCnpgCluster(emptyMap(), emptyMap(), now).postgresVersion)
    }

    @Test
    fun `postgresVersion is empty when image tag doesn't start with a digit`() {
        val c = cluster(spec = mapOf("imageName" to "internal/pg:custom"))
        assertEquals("", c.toCnpgCluster(emptyMap(), emptyMap(), now).postgresVersion)
    }

    @Test
    fun `postgresVersion is empty when image has no tag`() {
        val c = cluster(spec = mapOf("imageName" to "internal/pg"))
        assertEquals("", c.toCnpgCluster(emptyMap(), emptyMap(), now).postgresVersion)
    }

    @Test
    fun `postgresVersion is empty when image is blank`() {
        val c = cluster(spec = mapOf("imageName" to ""))
        assertEquals("", c.toCnpgCluster(emptyMap(), emptyMap(), now).postgresVersion)
    }

    // ===== Backup lookup =====

    @Test
    fun `backupSchedule resolves by namespace slash name`() {
        val c = cluster(name = "primary", namespace = "data")
        val schedules = mapOf("data/primary" to "0 2 * * *")
        val w = c.toCnpgCluster(schedules, emptyMap(), now)
        assertEquals("0 2 * * *", w.backupSchedule)
    }

    @Test
    fun `backupSchedule is empty when no entry`() {
        val c = cluster()
        assertEquals("", c.toCnpgCluster(emptyMap(), emptyMap(), now).backupSchedule)
    }

    @Test
    fun `lastBackup renders relative-time string from lookup`() {
        val c = cluster(name = "primary", namespace = "data")
        val lastBackup = mapOf("data/primary" to now.minusHours(2))
        assertEquals("2h ago", c.toCnpgCluster(emptyMap(), lastBackup, now).lastBackup)
    }

    @Test
    fun `lastBackup is empty when no entry`() {
        val c = cluster()
        assertEquals("", c.toCnpgCluster(emptyMap(), emptyMap(), now).lastBackup)
    }

    @Test
    fun `instances reads spec instances as int`() {
        val c = cluster(spec = mapOf("instances" to 3))
        assertEquals(3, c.toCnpgCluster(emptyMap(), emptyMap(), now).instances)
    }

    @Test
    fun `instances defaults to 0 when missing`() {
        val c = cluster()
        assertEquals(0, c.toCnpgCluster(emptyMap(), emptyMap(), now).instances)
    }

    @Test
    fun `primary reads status currentPrimary`() {
        val c = cluster(status = mapOf("currentPrimary" to "primary-1"))
        assertEquals("primary-1", c.toCnpgCluster(emptyMap(), emptyMap(), now).primary)
    }

    // ===== Instance topology (toCnpgInstance) =====

    private fun pod(
        name: String,
        namespace: String = "data",
        node: String? = null,
        labels: Map<String, String> = emptyMap(),
        phase: String = "Running",
        containers: List<Triple<Boolean, Int, String?>> = listOf(Triple(true, 0, null)),
        creationTimestamp: String? = null,
    ): Pod {
        val p = Pod()
        p.metadata = ObjectMetaBuilder().withName(name).withNamespace(namespace).build().apply {
            this.labels = labels
            this.creationTimestamp = creationTimestamp
        }
        p.spec = PodSpecBuilder()
            .withNodeName(node)
            .withContainers(containers.indices.map { ContainerBuilder().withName("c$it").withImage("img").build() })
            .build()
        p.status = PodStatusBuilder()
            .withPhase(phase)
            .withContainerStatuses(
                containers.mapIndexed { i, (ready, restarts, waitingReason) ->
                    val b = ContainerStatusBuilder()
                        .withName("c$i")
                        .withReady(ready)
                        .withRestartCount(restarts)
                    if (waitingReason != null) {
                        b.withState(ContainerStateBuilder().withNewWaiting().withReason(waitingReason).endWaiting().build())
                    }
                    b.build()
                },
            )
            .build()
        return p
    }

    @Test
    fun `instance role reads the cnpg instanceRole label`() {
        val i = pod("pg-1", labels = mapOf("cnpg.io/instanceRole" to "primary"))
            .toCnpgInstance(currentPrimary = "", zoneByNode = emptyMap(), pvcSizeByInstance = emptyMap(), now = now)
        assertEquals("primary", i.role)
    }

    @Test
    fun `instance role falls back to the bare role label`() {
        val i = pod("pg-2", labels = mapOf("role" to "replica"))
            .toCnpgInstance(currentPrimary = "pg-1", zoneByNode = emptyMap(), pvcSizeByInstance = emptyMap(), now = now)
        assertEquals("replica", i.role)
    }

    @Test
    fun `instance role falls back to currentPrimary comparison when no label`() {
        val primary = pod("pg-1").toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        val replica = pod("pg-2").toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        assertEquals("primary", primary.role)
        assertEquals("replica", replica.role)
    }

    @Test
    fun `instance is Ready when every container is ready`() {
        val i = pod("pg-1", containers = listOf(Triple(true, 0, null)))
            .toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        assertTrue(i.ready)
        assertEquals("Ready", i.status)
    }

    @Test
    fun `instance surfaces pod phase when not ready`() {
        val i = pod("pg-1", phase = "Pending", containers = listOf(Triple(false, 0, null)))
            .toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        assertFalse(i.ready)
        assertEquals("Pending", i.status)
    }

    @Test
    fun `instance surfaces a stuck waiting reason over the phase`() {
        val i = pod("pg-1", phase = "Running", containers = listOf(Triple(false, 7, "CrashLoopBackOff")))
            .toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        assertEquals("CrashLoopBackOff", i.status)
        assertEquals(7, i.restarts)
    }

    @Test
    fun `instance resolves zone from the node map and pvc size from the instance map`() {
        val i = pod("pg-1", node = "node-a")
            .toCnpgInstance(
                currentPrimary = "pg-1",
                zoneByNode = mapOf("node-a" to "us-east-1a"),
                pvcSizeByInstance = mapOf("pg-1" to "100Gi"),
                now = now,
            )
        assertEquals("node-a", i.node)
        assertEquals("us-east-1a", i.zone)
        assertEquals("100Gi", i.pvcSize)
    }

    @Test
    fun `instance leaves zone and pvc blank when unmapped`() {
        val i = pod("pg-1", node = "node-x").toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        assertEquals("", i.zone)
        assertEquals("", i.pvcSize)
    }

    @Test
    fun `instance age is rendered from the creation timestamp`() {
        val i = pod("pg-1", creationTimestamp = now.minusDays(5).toString())
            .toCnpgInstance("pg-1", emptyMap(), emptyMap(), now)
        assertEquals("5d", i.age)
    }

    // ===== Status-only instance fallback (no readable pod) =====

    @Test
    fun `status-only instance uses reported primary flag for role`() {
        val primary = cnpgInstanceFromStatus("pg-1", isPrimaryReported = true, currentPrimary = "", pvcSize = "50Gi")
        val replica = cnpgInstanceFromStatus("pg-2", isPrimaryReported = false, currentPrimary = "", pvcSize = "")
        assertEquals("primary", primary.role)
        assertEquals("replica", replica.role)
        assertEquals("50Gi", primary.pvcSize)
    }

    @Test
    fun `status-only instance falls back to currentPrimary when nothing is reported`() {
        val primary = cnpgInstanceFromStatus("pg-1", isPrimaryReported = null, currentPrimary = "pg-1", pvcSize = "")
        val replica = cnpgInstanceFromStatus("pg-2", isPrimaryReported = null, currentPrimary = "pg-1", pvcSize = "")
        assertEquals("primary", primary.role)
        assertEquals("replica", replica.role)
    }

    @Test
    fun `status-only instance leaves pod-derived fields blank and status Unknown`() {
        val i = cnpgInstanceFromStatus("pg-1", isPrimaryReported = true, currentPrimary = "pg-1", pvcSize = "")
        assertEquals("Unknown", i.status)
        assertFalse(i.ready)
        assertEquals("", i.node)
        assertEquals("", i.zone)
        assertEquals("", i.age)
        assertEquals(0, i.restarts)
    }

    // ===== Backups (toCnpgBackup) =====

    private fun backup(
        name: String = "b1",
        namespace: String = "data",
        spec: Map<String, Any?>? = null,
        status: Map<String, Any?>? = null,
    ): GenericKubernetesResource {
        val r = GenericKubernetesResourceBuilder().build()
        r.metadata = ObjectMetaBuilder().withName(name).withNamespace(namespace).build()
        r.kind = "Backup"
        r.apiVersion = "postgresql.cnpg.io/v1"
        if (spec != null) r.setAdditionalProperty("spec", spec)
        if (status != null) r.setAdditionalProperty("status", status)
        return r
    }

    @Test
    fun `backup maps method phase and relative timestamps`() {
        val b = backup(
            name = "pg-2026",
            spec = mapOf("method" to "barmanObjectStore"),
            status = mapOf(
                "phase" to "completed",
                "startedAt" to now.minusHours(3).toString(),
                "stoppedAt" to now.minusHours(2).toString(),
            ),
        ).toCnpgBackup(now)
        assertEquals("pg-2026", b.name)
        assertEquals("barmanObjectStore", b.method)
        assertEquals("completed", b.phase)
        assertEquals(WorkloadStatus.OK, b.statusKind)
        assertEquals("3h ago", b.started)
        assertEquals("2h ago", b.completed)
        assertEquals("1h 0m", b.duration)
    }

    @Test
    fun `backup with no stoppedAt leaves completed and duration blank`() {
        val b = backup(
            status = mapOf("phase" to "running", "startedAt" to now.minusMinutes(5).toString()),
        ).toCnpgBackup(now)
        assertEquals(WorkloadStatus.WARN, b.statusKind)
        assertEquals("5m ago", b.started)
        assertEquals("", b.completed)
        assertEquals("", b.duration)
    }

    @Test
    fun `failed backup maps to ERROR`() {
        val b = backup(status = mapOf("phase" to "failed")).toCnpgBackup(now)
        assertEquals(WorkloadStatus.ERROR, b.statusKind)
    }

    @Test
    fun `backup tolerates an absent status block`() {
        val b = backup(spec = mapOf("method" to "volumeSnapshot")).toCnpgBackup(now)
        assertEquals("volumeSnapshot", b.method)
        assertEquals("", b.phase)
        assertEquals(WorkloadStatus.WARN, b.statusKind)
        assertEquals("", b.started)
    }

    // ===== Detail assembly (toCnpgClusterDetail) =====

    @Test
    fun `detail extracts storage backup target retention and sorted parameters`() {
        val c = cluster(
            spec = mapOf(
                "storage" to mapOf("size" to "100Gi", "storageClass" to "gp3-encrypted"),
                "backup" to mapOf(
                    "retentionPolicy" to "30d",
                    "barmanObjectStore" to mapOf("destinationPath" to "s3://pg-backups/pg"),
                ),
                "postgresql" to mapOf(
                    "parameters" to mapOf("shared_buffers" to "1GB", "max_connections" to "300"),
                ),
            ),
        )
        val summary = c.toCnpgCluster(emptyMap(), emptyMap(), now)
        val detail = c.toCnpgClusterDetail(summary, instances = emptyList(), backups = emptyList())

        assertEquals("100Gi", detail.storageSize)
        assertEquals("gp3-encrypted", detail.storageClass)
        assertEquals("s3://pg-backups/pg", detail.backupDestinationPath)
        assertEquals("30d", detail.backupRetention)
        // Parameters are sorted by key.
        assertEquals(listOf("max_connections", "shared_buffers"), detail.parameters.map { it.key })
        assertEquals("300", detail.parameters.first { it.key == "max_connections" }.value)
    }

    @Test
    fun `detail leaves spec-derived fields blank when absent`() {
        val c = cluster()
        val detail = c.toCnpgClusterDetail(c.toCnpgCluster(emptyMap(), emptyMap(), now), emptyList(), emptyList())
        assertEquals("", detail.storageSize)
        assertEquals("", detail.storageClass)
        assertEquals("", detail.backupDestinationPath)
        assertEquals("", detail.backupRetention)
        assertTrue(detail.parameters.isEmpty())
    }
}
