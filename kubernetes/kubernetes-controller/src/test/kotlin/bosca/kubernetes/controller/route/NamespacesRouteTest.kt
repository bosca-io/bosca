package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.cluster.ClusterInformerRegistry
import bosca.kubernetes.controller.cluster.ClusterInformerSet
import bosca.kubernetes.controller.cluster.NamespaceCounts
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.NamespaceListBuilder
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [NamespacesRoute]. Counts come from the informer registry — the
 * route's job is to forward those counts unchanged and stitch them onto
 * each namespace row in lock-step with the fabric8 namespace list.
 */
@OptIn(ExperimentalUuidApi::class)
class NamespacesRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val informers = mockk<ClusterInformerRegistry>()
    private val informerSet = mockk<ClusterInformerSet>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String?): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns if (pathId != null)
            Parameters.fromSingleValueMap(mapOf("id" to pathId))
        else Parameters.Empty
        return call
    }

    @Test
    fun `happy path stitches informer counts onto each row`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { informers.get(id) } returns informerSet
        every { informerSet.counts("default") } returns NamespaceCounts(workloads = 2, pods = 5, services = 1)
        every { informerSet.counts("kube-system") } returns NamespaceCounts(workloads = 8, pods = 14, services = 3)
        every { client.namespaces().list() } returns NamespaceListBuilder()
            .addToItems(
                NamespaceBuilder()
                    .withNewMetadata().withName("default").endMetadata()
                    .withNewStatus().withPhase("Active").endStatus()
                    .build()
            )
            .addToItems(
                NamespaceBuilder()
                    .withNewMetadata().withName("kube-system").endMetadata()
                    .withNewStatus().withPhase("Active").endStatus()
                    .build()
            )
            .build()

        val r = NamespacesRoute(groups, pool, informers).runExecute(call(id.toString()), ctx)
        assertEquals(listOf("default", "kube-system"), r?.items?.map { it.name })
        assertEquals(2, r?.items?.get(0)?.workloads)
        assertEquals(5, r?.items?.get(0)?.pods)
        assertEquals(1, r?.items?.get(0)?.services)
        assertEquals(8, r?.items?.get(1)?.workloads)
        assertEquals(14, r?.items?.get(1)?.pods)
        assertEquals(3, r?.items?.get(1)?.services)
    }

    @Test
    fun `missing phase falls back to Unknown`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { informers.get(id) } returns informerSet
        every { informerSet.counts(any()) } returns NamespaceCounts(0, 0, 0)
        every { client.namespaces().list() } returns NamespaceListBuilder()
            .addToItems(NamespaceBuilder().withNewMetadata().withName("draft").endMetadata().build())
            .build()

        val r = NamespacesRoute(groups, pool, informers).runExecute(call(id.toString()), ctx)
        assertEquals("Unknown", r?.items?.single()?.status)
    }

    @Test
    fun `non-admin caller throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            NamespacesRoute(groups, pool, informers).runExecute(call(UUID.random().toString()), ctx)
        }
    }

    @Test
    fun `missing id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(null)
        val r = NamespacesRoute(groups, pool, informers).runExecute(c, ctx)
        assertNull(r)
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `unparseable id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call("not-a-uuid")
        val r = NamespacesRoute(groups, pool, informers).runExecute(c, ctx)
        assertNull(r)
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `fabric8 error propagates`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.namespaces().list() } throws RuntimeException("api down")
        assertFailsWith<RuntimeException> {
            NamespacesRoute(groups, pool, informers).runExecute(call(id.toString()), ctx)
        }
    }
}
