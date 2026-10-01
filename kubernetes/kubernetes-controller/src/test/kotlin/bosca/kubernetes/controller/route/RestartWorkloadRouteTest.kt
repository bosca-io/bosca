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
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
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
 * Pins [RestartWorkloadRoute]. The route mutates the workload's pod
 * template by stamping `kubectl.kubernetes.io/restartedAt`. Coverage:
 *   * Admin gate.
 *   * Path-parameter validation.
 *   * Per-kind editor invocation (Deployment / StatefulSet / DaemonSet).
 *   * Unsupported kinds (Job / CronJob / ReplicaSet) → 400.
 *   * Stamp preserves existing annotations.
 */
@OptIn(ExperimentalUuidApi::class)
class RestartWorkloadRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        return c
    }

    private fun templateWithAnnotation(extra: Map<String, String> = emptyMap()) = PodTemplateSpecBuilder()
        .withMetadata(ObjectMetaBuilder().withAnnotations<String, String>(extra).build())
        .withNewSpec().addToContainers(ContainerBuilder().withName("c").withImage("img").build()).endSpec()
        .build()

    private fun basePath(id: UUID, kind: String) = mapOf(
        "id" to id.toString(),
        "kind" to kind,
        "namespace" to "prod",
        "name" to "api",
    )

    @Test
    fun `restarts a deployment and stamps the annotation`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        var captured: Deployment? = null
        every {
            client.apps().deployments().inNamespace("prod").withName("api").edit(any<java.util.function.UnaryOperator<Deployment>>())
        } answers {
            val transform = firstArg<java.util.function.UnaryOperator<Deployment>>()
            val before = DeploymentBuilder()
                .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
                .withNewSpec().withReplicas(1).withTemplate(templateWithAnnotation(mapOf("existing" to "kept"))).endSpec()
                .build()
            captured = transform.apply(before)
            captured
        }

        val r = RestartWorkloadRoute(groups, pool).runExecute(call(basePath(id, "DEPLOYMENT")), ctx)
        assertEquals(true, r)
        val annotations = captured?.spec?.template?.metadata?.annotations
        assertTrue(annotations?.containsKey("kubectl.kubernetes.io/restartedAt") ?: false)
        assertEquals("kept", annotations?.get("existing"), "existing annotations are preserved")
    }

    @Test
    fun `restarts a statefulset`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every {
            client.apps().statefulSets().inNamespace("prod").withName("api").edit(any<java.util.function.UnaryOperator<StatefulSet>>())
        } answers {
            firstArg<java.util.function.UnaryOperator<StatefulSet>>().apply(
                StatefulSetBuilder()
                    .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
                    .withNewSpec().withReplicas(1).withTemplate(templateWithAnnotation()).endSpec()
                    .build()
            )
        }
        val r = RestartWorkloadRoute(groups, pool).runExecute(call(basePath(id, "STATEFUL_SET")), ctx)
        assertEquals(true, r)
    }

    @Test
    fun `restarts a daemonset`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every {
            client.apps().daemonSets().inNamespace("prod").withName("api").edit(any<java.util.function.UnaryOperator<DaemonSet>>())
        } answers {
            firstArg<java.util.function.UnaryOperator<DaemonSet>>().apply(
                DaemonSetBuilder()
                    .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
                    .withNewSpec().withTemplate(templateWithAnnotation()).endSpec()
                    .build()
            )
        }
        val r = RestartWorkloadRoute(groups, pool).runExecute(call(basePath(id, "DAEMON_SET")), ctx)
        assertEquals(true, r)
    }

    @Test
    fun `unsupported kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(basePath(id, "JOB"))
        assertNull(RestartWorkloadRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `bad kind enum returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val c = call(basePath(id, "BOGUS"))
        assertNull(RestartWorkloadRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }
}
