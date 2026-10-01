package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetListBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
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
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [PodsRoute] — admin gate, path-param parsing, the
 * controller-side filter ladder (`namespace` / `workloadId` / `search`)
 * and the offset/limit pagination. Filters apply before pagination so
 * `total` reflects the filtered set, which is what the studio's
 * `Showing N of M` indicator expects.
 */
@OptIn(ExperimentalUuidApi::class)
class PodsRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun pod(name: String, ns: String = "ns", workloadUid: String? = null): Pod {
        val md = ObjectMetaBuilder().withName(name).withNamespace(ns).withUid("uid-$name").apply {
            if (workloadUid != null) {
                addToOwnerReferences(OwnerReferenceBuilder().withKind("ReplicaSet").withName("rs").withUid(workloadUid).build())
            }
        }.build()
        return PodBuilder()
            .withMetadata(md)
            .withNewSpec().addToContainers(ContainerBuilder().withName("c").build()).endSpec()
            .build()
    }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return call
    }

    private fun stubPodList(items: List<Pod>, namespace: String? = null) {
        val list = PodListBuilder().withItems(items).build()
        if (namespace == null) {
            every { client.pods().inAnyNamespace().list() } returns list
        } else {
            every { client.pods().inNamespace(namespace).list() } returns list
        }
    }

    private fun replicaSet(uid: String, deploymentUid: String?): ReplicaSet {
        val md = ObjectMetaBuilder().withName("rs-$uid").withUid(uid).apply {
            if (deploymentUid != null) {
                addToOwnerReferences(OwnerReferenceBuilder().withKind("Deployment").withName("dep").withUid(deploymentUid).build())
            }
        }.build()
        return ReplicaSetBuilder().withMetadata(md).build()
    }

    private fun job(uid: String, cronJobUid: String?): Job {
        val md = ObjectMetaBuilder().withName("job-$uid").withUid(uid).apply {
            if (cronJobUid != null) {
                addToOwnerReferences(OwnerReferenceBuilder().withKind("CronJob").withName("cron").withUid(cronJobUid).build())
            }
        }.build()
        return JobBuilder().withMetadata(md).build()
    }

    private fun stubReplicaSets(items: List<ReplicaSet>, namespace: String? = null) {
        val list = ReplicaSetListBuilder().withItems(items).build()
        if (namespace == null) {
            every { client.apps().replicaSets().inAnyNamespace().list() } returns list
        } else {
            every { client.apps().replicaSets().inNamespace(namespace).list() } returns list
        }
    }

    private fun stubJobs(items: List<Job>, namespace: String? = null) {
        val list = JobListBuilder().withItems(items).build()
        if (namespace == null) {
            every { client.batch().v1().jobs().inAnyNamespace().list() } returns list
        } else {
            every { client.batch().v1().jobs().inNamespace(namespace).list() } returns list
        }
    }

    @Test
    fun `happy path returns total and items mapped to wire`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubPodList(listOf(pod("a"), pod("b"), pod("c")))

        val r = PodsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(3, r?.total)
        assertEquals(listOf("a", "b", "c"), r?.items?.map { it.name })
    }

    @Test
    fun `namespace filter routes through inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubPodList(items = listOf(pod("only", ns = "prod")), namespace = "prod")

        val r = PodsRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        assertEquals(1, r?.total)
        assertEquals("only", r?.items?.single()?.name)
    }

    @Test
    fun `workloadId filter narrows by owner-ref uid`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubPodList(listOf(
            pod("p1", workloadUid = "rs-1"),
            pod("p2", workloadUid = "rs-2"),
            pod("p3", workloadUid = "rs-1"),
        ))
        stubReplicaSets(emptyList())
        stubJobs(emptyList())

        val r = PodsRoute(groups, pool).runExecute(call(id.toString(), mapOf("workloadId" to "rs-1")), ctx)
        assertEquals(2, r?.total)
        assertEquals(listOf("p1", "p3"), r?.items?.map { it.name })
    }

    @Test
    fun `workloadId filter walks ReplicaSet to Deployment`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        // p1 + p3 are owned by ReplicaSets that belong to deployment-A;
        // p2 belongs to a ReplicaSet of a different deployment. Asking
        // for deployment-A should return p1 and p3 — even though their
        // direct owner UIDs are rs-A1 / rs-A2, not deployment-A itself.
        stubPodList(listOf(
            pod("p1", workloadUid = "rs-A1"),
            pod("p2", workloadUid = "rs-B"),
            pod("p3", workloadUid = "rs-A2"),
        ))
        stubReplicaSets(listOf(
            replicaSet(uid = "rs-A1", deploymentUid = "deployment-A"),
            replicaSet(uid = "rs-A2", deploymentUid = "deployment-A"),
            replicaSet(uid = "rs-B", deploymentUid = "deployment-B"),
        ))
        stubJobs(emptyList())

        val r = PodsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("workloadId" to "deployment-A")),
            ctx,
        )
        assertEquals(2, r?.total)
        assertEquals(listOf("p1", "p3"), r?.items?.map { it.name })
    }

    @Test
    fun `workloadId filter walks Job to CronJob`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        // p1 belongs to a Job owned by cron-A; p2 belongs to a Job that
        // has no CronJob owner (one-off Job). Asking for cron-A returns
        // only p1.
        stubPodList(listOf(
            pod("p1", workloadUid = "job-1"),
            pod("p2", workloadUid = "job-standalone"),
        ))
        stubReplicaSets(emptyList())
        stubJobs(listOf(
            job(uid = "job-1", cronJobUid = "cron-A"),
            job(uid = "job-standalone", cronJobUid = null),
        ))

        val r = PodsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("workloadId" to "cron-A")),
            ctx,
        )
        assertEquals(1, r?.total)
        assertEquals("p1", r?.items?.single()?.name)
    }

    @Test
    fun `workloadId filter still matches direct owner uid`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        // The studio sometimes already has the direct owner UID (e.g.
        // for a StatefulSet, where the StatefulSet *is* the direct
        // owner of its pods). Filtering by that UID must still match
        // even though no parent-walk applies.
        stubPodList(listOf(
            pod("p1", workloadUid = "sts-1"),
            pod("p2", workloadUid = "sts-2"),
        ))
        stubReplicaSets(emptyList())
        stubJobs(emptyList())

        val r = PodsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("workloadId" to "sts-1")),
            ctx,
        )
        assertEquals(1, r?.total)
        assertEquals("p1", r?.items?.single()?.name)
    }

    @Test
    fun `search filter is case-insensitive substring against name`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubPodList(listOf(pod("api-7df9"), pod("worker-abc"), pod("API-XYZ")))

        val r = PodsRoute(groups, pool).runExecute(call(id.toString(), mapOf("search" to "API")), ctx)
        assertEquals(2, r?.total)
        assertEquals(listOf("api-7df9", "API-XYZ"), r?.items?.map { it.name })
    }

    @Test
    fun `limit and offset paginate the filtered set after filtering`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        // 5 pods, search matches all 5; offset 1, limit 2.
        stubPodList((1..5).map { pod("p$it") })

        val r = PodsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("limit" to "2", "offset" to "1")),
            ctx,
        )
        assertEquals(5, r?.total, "total reflects the pre-pagination filtered set")
        assertEquals(listOf("p2", "p3"), r?.items?.map { it.name })
    }

    @Test
    fun `missing limit returns everything after offset`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubPodList(listOf(pod("a"), pod("b"), pod("c")))

        val r = PodsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("offset" to "1")),
            ctx,
        )
        assertEquals(3, r?.total)
        assertEquals(listOf("b", "c"), r?.items?.map { it.name })
    }

    @Test
    fun `non-admin caller throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        val r = runCatching { PodsRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx) }
        assert(r.isFailure)
        assert(r.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `missing id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.Empty
        val r = PodsRoute(groups, pool).runExecute(call, ctx)
        assertNull(r)
        verify { call.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `unparseable id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(pathId = "bad")
        val r = PodsRoute(groups, pool).runExecute(c, ctx)
        assertNull(r)
        verify { c.respond(HttpStatusCode.BadRequest) }
    }
}
