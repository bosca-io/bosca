package bosca.kubernetes.controller

import bosca.kubernetes.model.Cluster
import bosca.kubernetes.model.ClusterEnvironment
import bosca.kubernetes.model.Namespace
import bosca.kubernetes.model.NamespacesResponse
import bosca.kubernetes.service.ClusterService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

/**
 * Resolver-layer tests for [KubernetesQueriesController].
 *
 * The contract: every resolver checks admin membership, verifies the
 * cluster exists in Bosca's registry, then delegates to the
 * kubernetes-controller HTTP client. These tests pin that pipeline and
 * keep the (cluster-id-as-UUID) shape from regressing — earlier the
 * schema declared `cluster: ID!` and graphql-java handed the resolver a
 * raw `String` that crashed the kotlin.uuid.Uuid cast.
 */
@OptIn(ExperimentalUuidApi::class)
class KubernetesQueriesControllerTest {

    private val clusters = mockk<ClusterService>()
    private val controller = mockk<KubernetesControllerClient>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val clusterId = UUID.random()

    private lateinit var ctrl: KubernetesQueriesController

    @BeforeTest
    fun setup() {
        ctrl = KubernetesQueriesController(clusters, controller, groups)
    }

    @AfterTest
    fun teardown() {
        io.mockk.unmockkAll()
    }

    private fun fakeCluster(id: UUID = clusterId) = Cluster(
        id = id,
        name = "test",
        provider = "kind",
        region = "local",
        environment = ClusterEnvironment.DEVELOPMENT,
    )

    @Test
    fun `cluster lookup runs admin check before hitting the repo`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()

        val result = ctrl.cluster(auth, clusterId)

        assertEquals(clusterId, result?.id)
        io.mockk.verify { groups.verifyHasAdminGroup(auth) }
        io.mockk.coVerify { clusters.getById(clusterId) }
    }

    @Test
    fun `namespaces returns the controller client's items list`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        coEvery { controller.namespaces(auth, clusterId) } returns NamespacesResponse(
            items = listOf(
                Namespace(name = "default", status = "Active", workloads = 2, pods = 2, services = 1, age = "1d"),
                Namespace(name = "kube-system", status = "Active", workloads = 4, pods = 8, services = 3, age = "5d"),
            ),
        )

        val out = ctrl.namespaces(auth, clusterId)

        assertEquals(2, out.size)
        assertEquals("default", out[0].name)
        assertEquals("kube-system", out[1].name)
    }

    @Test
    fun `namespaces fails fast when the cluster is not registered`() = runTest {
        coEvery { clusters.getById(clusterId) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            ctrl.namespaces(auth, clusterId)
        }
        assertEquals("Cluster $clusterId not found", ex.message)
        // The controller client must NOT be called for an unregistered cluster.
        io.mockk.coVerify(exactly = 0) { controller.namespaces(any(), any()) }
    }

    @Test
    fun `admin failure shortcircuits before any cluster lookup`() = runTest {
        every { groups.verifyHasAdminGroup(auth) } throws SecurityException("not admin")

        assertFailsWith<SecurityException> {
            ctrl.namespaces(auth, clusterId)
        }
        io.mockk.coVerify(exactly = 0) { clusters.getById(any()) }
        io.mockk.coVerify(exactly = 0) { controller.namespaces(any(), any()) }
    }

    @Test
    fun `helmReleaseValues delegates to the controller client`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        coEvery {
            controller.helmReleaseValues(auth, clusterId, "default", "podinfo", null)
        } returns "image:\n  tag: latest\n"

        val yaml = ctrl.helmReleaseValues(auth, clusterId, "default", "podinfo")

        assertEquals("image:\n  tag: latest\n", yaml)
    }

    @Test
    fun `helmReleaseValues propagates the revision arg`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        coEvery {
            controller.helmReleaseValues(auth, clusterId, "default", "podinfo", 3)
        } returns "rev3"

        val yaml = ctrl.helmReleaseValues(auth, clusterId, "default", "podinfo", 3)

        assertEquals("rev3", yaml)
    }

    @Test
    fun `helmReleaseManifest delegates to the controller client`() = runTest {
        coEvery { clusters.getById(clusterId) } returns fakeCluster()
        coEvery {
            controller.helmReleaseManifest(auth, clusterId, "default", "podinfo", null)
        } returns "kind: Deployment\nmetadata:\n  name: podinfo\n"

        val yaml = ctrl.helmReleaseManifest(auth, clusterId, "default", "podinfo")

        assertEquals("kind: Deployment\nmetadata:\n  name: podinfo\n", yaml)
    }
}
