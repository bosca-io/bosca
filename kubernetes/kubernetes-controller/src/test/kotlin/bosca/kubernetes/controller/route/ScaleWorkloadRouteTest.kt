package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ScaleWorkloadRoute]. We exercise each supported workload kind
 * (Deployment / StatefulSet / ReplicaSet), assert the unsupported kinds
 * (DaemonSet / Job / CronJob) get 400, and verify the path/parameter
 * validation path.
 */
@OptIn(ExperimentalUuidApi::class)
class ScaleWorkloadRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>, body: String = """{"replicas":3}"""): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.contentType() } returns null
        coEvery { c.request.bodyText() } returns body
        every { c.application.json } returns Json { ignoreUnknownKeys = true; explicitNulls = false }
        return c
    }

    private fun template() = PodTemplateSpecBuilder()
        .withNewSpec().addToContainers(ContainerBuilder().withName("c").withImage("img").build()).endSpec()
        .build()

    private fun deployment(name: String, ns: String, replicas: Int) = DeploymentBuilder()
        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
        .withNewSpec().withReplicas(replicas).withTemplate(template()).endSpec()
        .build()

    private fun statefulSet(name: String, ns: String, replicas: Int) = StatefulSetBuilder()
        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
        .withNewSpec().withReplicas(replicas).withTemplate(template()).endSpec()
        .build()

    private fun replicaSet(name: String, ns: String, replicas: Int) = ReplicaSetBuilder()
        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
        .withNewSpec().withReplicas(replicas).withTemplate(template()).endSpec()
        .build()

    private fun basePath(id: UUID, kind: String) = mapOf(
        "id" to id.toString(),
        "kind" to kind,
        "namespace" to "prod",
        "name" to "api",
    )

    @Test
    fun `scales a deployment`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.apps().deployments().inNamespace("prod").withName("api").scale(3) } returns
            deployment("api", "prod", 3)

        val r = ScaleWorkloadRoute(groups, pool).runExecute(call(basePath(id, "DEPLOYMENT")), ctx)
        assertEquals("api", r?.name)
        verify { client.apps().deployments().inNamespace("prod").withName("api").scale(3) }
    }

    @Test
    fun `scales a statefulset`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.apps().statefulSets().inNamespace("prod").withName("api").scale(3) } returns
            statefulSet("api", "prod", 3)

        ScaleWorkloadRoute(groups, pool).runExecute(call(basePath(id, "STATEFUL_SET")), ctx)
        verify { client.apps().statefulSets().inNamespace("prod").withName("api").scale(3) }
    }

    @Test
    fun `scales a replicaset`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.apps().replicaSets().inNamespace("prod").withName("api").scale(3) } returns
            replicaSet("api", "prod", 3)

        ScaleWorkloadRoute(groups, pool).runExecute(call(basePath(id, "REPLICA_SET")), ctx)
        verify { client.apps().replicaSets().inNamespace("prod").withName("api").scale(3) }
    }

    @Test
    fun `unsupported kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(basePath(id, "DAEMON_SET"))
        assertNull(ScaleWorkloadRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `negative replicas returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(basePath(id, "DEPLOYMENT"), body = """{"replicas":-1}""")
        assertNull(ScaleWorkloadRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `invalid kind enum returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(basePath(id, "WIDGET"))
        assertNull(ScaleWorkloadRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing namespace returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val c = call(mapOf("id" to id.toString(), "kind" to "DEPLOYMENT", "name" to "api"))
        assertNull(ScaleWorkloadRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }
}
