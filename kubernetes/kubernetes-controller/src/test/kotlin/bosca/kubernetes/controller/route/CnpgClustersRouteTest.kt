package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [CnpgClustersRoute] — Cluster listing, schedule stitching from
 * ScheduledBackup, and lastBackup stitching from Backup. Missing CRDs
 * short-circuit to empty.
 */
@OptIn(ExperimentalUuidApi::class)
class CnpgClustersRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
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

    @Test
    fun `clusters route returns mapped clusters with schedule and last backup stitched`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Cluster").listInAnyNamespace(
            gkr(
                "Cluster", "db", "prod",
                spec = mapOf("instances" to 3, "imageName" to "ghcr.io/cnpg/postgresql:16.4"),
                status = mapOf("phase" to "Cluster in healthy state", "currentPrimary" to "db-1"),
            ),
        )
        d.forKind("ScheduledBackup").listInAnyNamespace(
            gkr(
                "ScheduledBackup", "nightly", "prod",
                spec = mapOf("schedule" to "0 2 * * *", "cluster" to mapOf("name" to "db")),
            ),
        )
        d.forKind("Backup").listInAnyNamespace(
            gkr(
                "Backup", "b1", "prod",
                spec = mapOf("cluster" to mapOf("name" to "db")),
                status = mapOf("phase" to "completed", "stoppedAt" to "2026-05-15T10:00:00Z"),
            ),
        )

        val r = CnpgClustersRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val cluster = r?.items?.single()
        assertEquals("db", cluster?.name)
        assertEquals("0 2 * * *", cluster?.backupSchedule)
        assertTrue(cluster?.lastBackup?.isNotBlank() ?: false)
    }

    @Test
    fun `clusters route routes through inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Cluster").listInNamespace(
            "prod",
            gkr("Cluster", "db", "prod", spec = mapOf("instances" to 3)),
        )
        d.forKind("ScheduledBackup").listInNamespace("prod")
        d.forKind("Backup").listInNamespace("prod")

        val r = CnpgClustersRoute(groups, pool)
            .runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        assertEquals("prod", r?.items?.single()?.namespace)
    }

    @Test
    fun `clusters route returns empty when Cluster CRD is missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Cluster").throwsOnInAnyNamespace(RuntimeException("no CRD"))

        val r = CnpgClustersRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `schedules and backups tolerate missing sibling CRDs`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Cluster").listInAnyNamespace(
            gkr("Cluster", "db", "prod", spec = mapOf("instances" to 3))
        )
        d.forKind("ScheduledBackup").throwsOnInAnyNamespace(RuntimeException("no CRD"))
        d.forKind("Backup").throwsOnInAnyNamespace(RuntimeException("no CRD"))

        val r = CnpgClustersRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val cluster = r?.items?.single()
        assertEquals("", cluster?.backupSchedule)
        assertEquals("", cluster?.lastBackup)
    }
}
