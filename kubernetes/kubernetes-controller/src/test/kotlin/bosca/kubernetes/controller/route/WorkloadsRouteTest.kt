package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.WorkloadKind
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetListBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentListBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetListBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetListBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobListBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobListBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [WorkloadsRoute]. The route fans out across the six workload
 * kinds when no `kind` filter is set, and narrows to a single kind
 * otherwise. We assert each kind's wire mapping shows up and that the
 * `namespace` query routes through `inNamespace` vs `inAnyNamespace`.
 */
@OptIn(ExperimentalUuidApi::class)
class WorkloadsRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    private fun template(image: String = "img:1") = PodTemplateSpecBuilder()
        .withNewSpec().addToContainers(ContainerBuilder().withName("c").withImage(image).build()).endSpec()
        .build()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun stubAllSixCluster() {
        every { client.apps().deployments().inAnyNamespace().list() } returns
            DeploymentListBuilder().addToItems(
                DeploymentBuilder().withNewMetadata().withName("d").withNamespace("ns").endMetadata()
                    .withNewSpec().withReplicas(1).withTemplate(template()).endSpec().build()
            ).build()
        every { client.apps().statefulSets().inAnyNamespace().list() } returns
            StatefulSetListBuilder().addToItems(
                StatefulSetBuilder().withNewMetadata().withName("s").withNamespace("ns").endMetadata()
                    .withNewSpec().withReplicas(1).withTemplate(template()).endSpec().build()
            ).build()
        every { client.apps().daemonSets().inAnyNamespace().list() } returns
            DaemonSetListBuilder().addToItems(
                DaemonSetBuilder().withNewMetadata().withName("ds").withNamespace("ns").endMetadata()
                    .withNewSpec().withTemplate(template()).endSpec().build()
            ).build()
        every { client.apps().replicaSets().inAnyNamespace().list() } returns
            ReplicaSetListBuilder().addToItems(
                ReplicaSetBuilder().withNewMetadata().withName("rs").withNamespace("ns").endMetadata()
                    .withNewSpec().withReplicas(1).withTemplate(template()).endSpec().build()
            ).build()
        every { client.batch().v1().jobs().inAnyNamespace().list() } returns
            JobListBuilder().addToItems(
                JobBuilder().withNewMetadata().withName("j").withNamespace("ns").endMetadata()
                    .withNewSpec().withTemplate(template()).endSpec().build()
            ).build()
        every { client.batch().v1().cronjobs().inAnyNamespace().list() } returns
            CronJobListBuilder().addToItems(
                CronJobBuilder().withNewMetadata().withName("cj").withNamespace("ns").endMetadata()
                    .withNewSpec()
                        .withSchedule("@daily")
                        .withNewJobTemplate().withNewSpec().withTemplate(template()).endSpec().endJobTemplate()
                    .endSpec().build()
            ).build()
        // Pod list is consumed by the route to compute cpu/memory/restart
        // aggregates per workload. Empty list is fine for the existing
        // tests — they assert kind enumeration, not metric values.
        every { client.pods().inAnyNamespace().list() } returns PodListBuilder().build()
    }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return call
    }

    @Test
    fun `no filter fans out across all six kinds`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubAllSixCluster()

        val r = WorkloadsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val kinds = r?.items?.map { it.kind }?.toSet()
        assertEquals(setOf(
            WorkloadKind.DEPLOYMENT, WorkloadKind.STATEFUL_SET, WorkloadKind.DAEMON_SET,
            WorkloadKind.REPLICA_SET, WorkloadKind.JOB, WorkloadKind.CRON_JOB,
        ), kinds)
    }

    @Test
    fun `kind filter narrows to a single kind`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        // We always fetch every kind so the pod-ownership chain
        // (Deployment ← ReplicaSet ← Pod, CronJob ← Job ← Pod) can be
        // walked end-to-end for cpu / memory / restart aggregates;
        // `kind` is applied as a post-filter on the assembled list.
        stubAllSixCluster()

        val r = WorkloadsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "DEPLOYMENT")),
            ctx,
        )
        assertEquals(1, r?.items?.size)
        assertEquals(WorkloadKind.DEPLOYMENT, r?.items?.single()?.kind)
        assertEquals("d", r?.items?.single()?.name)
        verify { client.apps().deployments().inAnyNamespace().list() }
    }

    @Test
    fun `unknown kind filter is ignored and fans out across all six`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubAllSixCluster()

        val r = WorkloadsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "BOGUS")),
            ctx,
        )
        // valueOf failed → filter is null → fan-out across all six kinds.
        assertEquals(6, r?.items?.size)
    }

    @Test
    fun `namespace filter routes through inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        // Every kind goes through the namespaced ops; the pod list
        // for aggregates does too. Stub each so the strict mock
        // doesn't blow up — the assertion is just that the
        // namespace argument lands on the call.
        every { client.apps().deployments().inNamespace("prod").list() } returns
            DeploymentListBuilder().addToItems(
                DeploymentBuilder().withNewMetadata().withName("api").withNamespace("prod").endMetadata()
                    .withNewSpec().withReplicas(1).withTemplate(template()).endSpec().build()
            ).build()
        every { client.apps().statefulSets().inNamespace("prod").list() } returns StatefulSetListBuilder().build()
        every { client.apps().daemonSets().inNamespace("prod").list() } returns DaemonSetListBuilder().build()
        every { client.apps().replicaSets().inNamespace("prod").list() } returns ReplicaSetListBuilder().build()
        every { client.batch().v1().jobs().inNamespace("prod").list() } returns JobListBuilder().build()
        every { client.batch().v1().cronjobs().inNamespace("prod").list() } returns CronJobListBuilder().build()
        every { client.pods().inNamespace("prod").list() } returns PodListBuilder().build()

        val r = WorkloadsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("namespace" to "prod", "kind" to "DEPLOYMENT")),
            ctx,
        )
        assertEquals("prod", r?.items?.single()?.namespace)
        verify { client.apps().deployments().inNamespace("prod").list() }
    }

    @Test
    fun `missing id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.Empty
        val r = WorkloadsRoute(groups, pool).runExecute(call, ctx)
        assertNull(r)
        verify { call.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        val r = runCatching { WorkloadsRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx) }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is SecurityException)
    }
}
