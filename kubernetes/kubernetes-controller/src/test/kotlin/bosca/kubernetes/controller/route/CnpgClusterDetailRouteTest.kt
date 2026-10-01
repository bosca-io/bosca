package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeListBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimListBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [CnpgClusterDetailRoute] — the orchestration that joins the
 * `Cluster` CRD, its `cnpg.io/cluster` pods (with node zone + bound
 * PVC), the `Backup` history, the `ScheduledBackup` schedule, and the
 * spec-derived storage/parameters into one detail payload. Field-level
 * mapping is covered by `CnpgMapperTest`; here we verify the wiring,
 * the not-found path, and graceful degradation when siblings are
 * missing.
 */
@OptIn(ExperimentalUuidApi::class)
class CnpgClusterDetailRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>(relaxed = true)

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(id: String, namespace: String = "data", name: String = "pg"): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns
            Parameters.fromSingleValueMap(mapOf("id" to id, "namespace" to namespace, "name" to name))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(emptyMap())
        return call
    }

    private fun gkr(
        kind: String,
        name: String,
        namespace: String,
        spec: Map<String, Any?>? = null,
        status: Map<String, Any?>? = null,
    ): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.metadata = ObjectMetaBuilder().withName(name).withNamespace(namespace).build()
        r.kind = kind
        r.apiVersion = "postgresql.cnpg.io/v1"
        if (spec != null) r.additionalProperties["spec"] = spec
        if (status != null) r.additionalProperties["status"] = status
        return r
    }

    private fun instancePod(name: String, node: String, role: String, ready: Boolean) =
        PodBuilder()
            .withNewMetadata().withName(name).withNamespace("data").addToLabels("cnpg.io/instanceRole", role).endMetadata()
            .withNewSpec().withNodeName(node).addNewContainer().withName("postgres").withImage("img").endContainer().endSpec()
            .withNewStatus().withPhase("Running")
            .addNewContainerStatus().withName("postgres").withReady(ready).withRestartCount(0).endContainerStatus()
            .endStatus()
            .build()

    @Test
    fun `detail route assembles summary, instances, backups, schedule and parameters`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()

        // Cluster CRD (single get), with storage + parameters spec.
        every { d.forKind("Cluster").ops.inNamespace("data").withName("pg").get() } returns gkr(
            "Cluster", "pg", "data",
            spec = mapOf(
                "instances" to 2,
                "imageName" to "ghcr.io/cnpg/postgresql:16.4",
                "storage" to mapOf("size" to "100Gi", "storageClass" to "gp3"),
                "backup" to mapOf("retentionPolicy" to "30d", "barmanObjectStore" to mapOf("destinationPath" to "s3://b/pg")),
                "postgresql" to mapOf("parameters" to mapOf("max_connections" to "300")),
            ),
            status = mapOf("phase" to "Cluster in healthy state", "currentPrimary" to "pg-1"),
        )
        d.forKind("ScheduledBackup").listInNamespace(
            "data",
            gkr("ScheduledBackup", "nightly", "data", spec = mapOf("schedule" to "0 2 * * *", "cluster" to mapOf("name" to "pg"))),
        )
        d.forKind("Backup").listInNamespace(
            "data",
            gkr("Backup", "pg-b1", "data",
                spec = mapOf("method" to "barmanObjectStore", "cluster" to mapOf("name" to "pg")),
                status = mapOf("phase" to "completed", "startedAt" to "2026-05-14T10:00:00Z", "stoppedAt" to "2026-05-14T10:30:00Z")),
            // a backup for a different cluster must be filtered out
            gkr("Backup", "other-b1", "data",
                spec = mapOf("cluster" to mapOf("name" to "other")),
                status = mapOf("phase" to "completed")),
        )

        every { client.pods().inNamespace("data").withLabel("cnpg.io/cluster", "pg").list() } returns
            PodListBuilder().addToItems(
                instancePod("pg-1", "node-a", "primary", ready = true),
                instancePod("pg-2", "node-b", "replica", ready = true),
            ).build()
        every { client.nodes().list() } returns NodeListBuilder().addToItems(
            NodeBuilder().withNewMetadata().withName("node-a").addToLabels("topology.kubernetes.io/zone", "us-east-1a").endMetadata().build(),
            NodeBuilder().withNewMetadata().withName("node-b").addToLabels("topology.kubernetes.io/zone", "us-east-1b").endMetadata().build(),
        ).build()
        every { client.persistentVolumeClaims().inNamespace("data").list() } returns
            PersistentVolumeClaimListBuilder().addToItems(
                PersistentVolumeClaimBuilder().withNewMetadata().withName("pg-1").withNamespace("data").endMetadata()
                    .withNewStatus().addToCapacity("storage", Quantity("100Gi")).endStatus().build(),
                PersistentVolumeClaimBuilder().withNewMetadata().withName("pg-2").withNamespace("data").endMetadata()
                    .withNewStatus().addToCapacity("storage", Quantity("100Gi")).endStatus().build(),
            ).build()

        val detail = assertNotNull(CnpgClusterDetailRoute(groups, pool).runExecute(call(id.toString()), ctx)?.detail)
        assertEquals("pg", detail.cluster.name)
        assertEquals("16.4", detail.cluster.postgresVersion)
        assertEquals("0 2 * * *", detail.cluster.backupSchedule)
        assertTrue(detail.cluster.lastBackup.isNotBlank())

        assertEquals("100Gi", detail.storageSize)
        assertEquals("gp3", detail.storageClass)
        assertEquals("s3://b/pg", detail.backupDestinationPath)
        assertEquals("30d", detail.backupRetention)

        assertEquals(listOf("pg-1", "pg-2"), detail.instances.map { it.name })
        assertEquals("primary", detail.instances.first().role)
        assertEquals("us-east-1a", detail.instances.first().zone)
        assertEquals("100Gi", detail.instances.first().pvcSize)

        // only this cluster's backup survives the filter
        assertEquals(listOf("pg-b1"), detail.backups.map { it.name })
        assertEquals("30m 0s", detail.backups.first().duration)

        assertEquals(listOf("max_connections"), detail.parameters.map { it.key })
    }

    @Test
    fun `detail route lists instances from cluster status when pods are unreadable`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        every { d.forKind("Cluster").ops.inNamespace("data").withName("pg").get() } returns gkr(
            "Cluster", "pg", "data",
            spec = mapOf("instances" to 3),
            status = mapOf(
                "currentPrimary" to "pg-1",
                "instanceNames" to listOf("pg-2", "pg-1", "pg-3"),
                "instancesReportedState" to mapOf(
                    "pg-1" to mapOf("isPrimary" to true),
                    "pg-2" to mapOf("isPrimary" to false),
                ),
            ),
        )
        // Pods are RBAC-blocked — the table must still list instances from the Cluster status.
        every { client.pods().inNamespace("data").withLabel("cnpg.io/cluster", "pg").list() } throws RuntimeException("pods forbidden")
        // PVCs remain readable and enrich the volume column by instance name.
        every { client.persistentVolumeClaims().inNamespace("data").list() } returns
            PersistentVolumeClaimListBuilder().addToItems(
                PersistentVolumeClaimBuilder().withNewMetadata().withName("pg-1").withNamespace("data").endMetadata()
                    .withNewStatus().addToCapacity("storage", Quantity("50Gi")).endStatus().build(),
            ).build()

        val detail = assertNotNull(CnpgClusterDetailRoute(groups, pool).runExecute(call(id.toString()), ctx)?.detail)
        assertEquals(listOf("pg-1", "pg-2", "pg-3"), detail.instances.map { it.name })
        assertEquals("primary", detail.instances.first { it.name == "pg-1" }.role)
        assertEquals("replica", detail.instances.first { it.name == "pg-2" }.role)
        // pg-3 has no reported state → role falls back to currentPrimary comparison.
        assertEquals("replica", detail.instances.first { it.name == "pg-3" }.role)
        assertEquals("Unknown", detail.instances.first().status)
        assertEquals("50Gi", detail.instances.first { it.name == "pg-1" }.pvcSize)
    }

    @Test
    fun `detail route returns null detail when the cluster is absent`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        every { d.forKind("Cluster").ops.inNamespace("data").withName("pg").get() } returns null

        val result = CnpgClusterDetailRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertNull(result?.detail)
    }

    @Test
    fun `detail route tolerates missing sibling resources`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        every { d.forKind("Cluster").ops.inNamespace("data").withName("pg").get() } returns
            gkr("Cluster", "pg", "data", spec = mapOf("instances" to 1))
        d.forKind("ScheduledBackup").throwsOnInNamespace("data", RuntimeException("no CRD"))
        d.forKind("Backup").throwsOnInNamespace("data", RuntimeException("no CRD"))
        every { client.pods().inNamespace("data").withLabel("cnpg.io/cluster", "pg").list() } throws RuntimeException("no perms")
        every { client.nodes().list() } throws RuntimeException("no perms")
        every { client.persistentVolumeClaims().inNamespace("data").list() } throws RuntimeException("no perms")

        val detail = assertNotNull(CnpgClusterDetailRoute(groups, pool).runExecute(call(id.toString()), ctx)?.detail)
        assertEquals("pg", detail.cluster.name)
        assertEquals("", detail.cluster.backupSchedule)
        assertEquals("", detail.cluster.lastBackup)
        assertTrue(detail.instances.isEmpty())
        assertTrue(detail.backups.isEmpty())
        assertTrue(detail.parameters.isEmpty())
    }
}
