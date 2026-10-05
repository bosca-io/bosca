package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [WorkloadStatusWatchRoute] — six-kind switch + erased watcher
 * + DELETED terminates the stream early. We exercise each kind's
 * watch dispatch and the DELETED-as-terminator behavior.
 */
@OptIn(ExperimentalUuidApi::class)
class WorkloadStatusWatchRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        return c
    }

    private fun template() = PodTemplateSpecBuilder()
        .withNewSpec().addToContainers(ContainerBuilder().withName("c").withImage("img").build()).endSpec()
        .build()

    private fun deployment() = DeploymentBuilder()
        .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
        .withNewSpec().withReplicas(1).withTemplate(template()).endSpec()
        .build()

    private fun statefulSet() = StatefulSetBuilder()
        .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
        .withNewSpec().withReplicas(1).withTemplate(template()).endSpec()
        .build()

    private fun daemonSet() = DaemonSetBuilder()
        .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
        .withNewSpec().withTemplate(template()).endSpec()
        .build()

    private fun replicaSet() = ReplicaSetBuilder()
        .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
        .withNewSpec().withReplicas(1).withTemplate(template()).endSpec()
        .build()

    private fun job() = JobBuilder()
        .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
        .withNewSpec().withTemplate(template()).endSpec()
        .build()

    private fun cronJob() = CronJobBuilder()
        .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
        .withNewSpec()
            .withSchedule("@daily")
            .withNewJobTemplate().withNewSpec().withTemplate(template()).endSpec().endJobTemplate()
        .endSpec()
        .build()

    /** Drives the captured watcher with one MODIFIED event then closes. */
    private fun <T : HasMetadata> answerWatch(slot: io.mockk.CapturingSlot<Watcher<T>>, watch: Watch, resource: T): Watch {
        val w = slot.captured
        w.eventReceived(Watcher.Action.MODIFIED, resource)
        w.onClose(null)
        return watch
    }

    private fun basePath(id: UUID, kind: String) = mapOf(
        "id" to id.toString(),
        "kind" to kind,
        "namespace" to "prod",
        "name" to "api",
    )

    @Test
    fun `Deployment watch emits a Workload then closes`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<Deployment>>()
        every {
            client.apps().deployments().inNamespace("prod").withName("api").watch(capture(slot))
        } answers { answerWatch(slot, watch, deployment()) }

        val c = call(basePath(id, "DEPLOYMENT"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
        verify { watch.close() }
    }

    @Test
    fun `StatefulSet watch dispatches through statefulSets chain`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<StatefulSet>>()
        every {
            client.apps().statefulSets().inNamespace("prod").withName("api").watch(capture(slot))
        } answers { answerWatch(slot, watch, statefulSet()) }

        val c = call(basePath(id, "STATEFUL_SET"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `DaemonSet watch dispatches through daemonSets chain`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<DaemonSet>>()
        every {
            client.apps().daemonSets().inNamespace("prod").withName("api").watch(capture(slot))
        } answers { answerWatch(slot, watch, daemonSet()) }

        val c = call(basePath(id, "DAEMON_SET"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `ReplicaSet watch dispatches through replicaSets chain`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<ReplicaSet>>()
        every {
            client.apps().replicaSets().inNamespace("prod").withName("api").watch(capture(slot))
        } answers { answerWatch(slot, watch, replicaSet()) }

        val c = call(basePath(id, "REPLICA_SET"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `Job watch dispatches through batch v1 jobs`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<Job>>()
        every {
            client.batch().v1().jobs().inNamespace("prod").withName("api").watch(capture(slot))
        } answers { answerWatch(slot, watch, job()) }

        val c = call(basePath(id, "JOB"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `CronJob watch dispatches through batch v1 cronjobs`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<CronJob>>()
        every {
            client.batch().v1().cronjobs().inNamespace("prod").withName("api").watch(capture(slot))
        } answers { answerWatch(slot, watch, cronJob()) }

        val c = call(basePath(id, "CRON_JOB"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `DELETED terminates the stream`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<Deployment>>()
        every {
            client.apps().deployments().inNamespace("prod").withName("api").watch(capture(slot))
        } answers {
            val w = slot.captured
            w.eventReceived(Watcher.Action.MODIFIED, deployment())
            w.eventReceived(Watcher.Action.DELETED, deployment())  // closes channel
            watch
        }

        val c = call(basePath(id, "DEPLOYMENT"))
        val cap = c.captureStream()
        WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        // Only the MODIFIED event preceded the DELETED close.
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `unknown kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(basePath(id, "WIDGET"))
        assertNull(WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing path parameters return 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val c = call(mapOf("id" to id.toString(), "kind" to "DEPLOYMENT", "name" to "api"))
        assertNull(WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `watch is closed when cancellation lands during acquisition`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        lateinit var routeJob: CoroutineJob
        every {
            client.apps().deployments().inNamespace("prod").withName("api").watch(any())
        } answers {
            routeJob.cancel(CancellationException("client disconnected"))
            watch
        }

        val c = call(basePath(id, "DEPLOYMENT"))
        c.captureStream()
        routeJob = launch(start = CoroutineStart.LAZY) {
            WorkloadStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        }

        routeJob.start()
        routeJob.join()

        verify { watch.close() }
    }
}
