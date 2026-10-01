package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.NodeListBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
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
 * Pins [NodesRoute]. Same scaffolding as every list route — admin gate,
 * path-param parsing, fabric8 list call — so this test doubles as the
 * shape reference for the rest of the route suite.
 */
@OptIn(ExperimentalUuidApi::class)
class NodesRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()

    private fun call(pathId: String?): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns if (pathId != null)
            Parameters.fromSingleValueMap(mapOf("id" to pathId))
        else Parameters.Empty
        return call
    }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    @Test
    fun `happy path maps fabric8 nodes to the wire shape`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val client = mockk<KubernetesClient>(relaxed = true)
        val list = NodeListBuilder()
            .addToItems(
                NodeBuilder()
                    .withNewMetadata().withName("n1").endMetadata()
                    .withNewStatus()
                        .addToConditions(NodeConditionBuilder().withType("Ready").withStatus("True").build())
                    .endStatus()
                    .build()
            )
            .addToItems(
                NodeBuilder()
                    .withNewMetadata().withName("n2").endMetadata()
                    .withNewStatus()
                        .addToConditions(NodeConditionBuilder().withType("Ready").withStatus("False").build())
                    .endStatus()
                    .build()
            )
            .build()
        every { client.nodes().list() } returns list
        // Pod list backs the per-node pod count; metrics fetch is
        // wrapped in runCatching so an unstubbed `client.top()` falls
        // through gracefully.
        every { client.pods().inAnyNamespace().list() } returns PodListBuilder().build()
        coEvery { pool.get(id) } returns client

        val response = NodesRoute(groups, pool).runExecute(call(id.toString()), ctx)

        assertEquals(2, response?.items?.size)
        assertEquals(listOf("n1", "n2"), response?.items?.map { it.name })
        assertEquals("Ready", response?.items?.get(0)?.status)
        assertEquals("NotReady", response?.items?.get(1)?.status)
    }

    @Test
    fun `non-admin caller throws and never touches the pool`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> {
            NodesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
    }

    @Test
    fun `missing id returns null and emits 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(pathId = null)
        val r = NodesRoute(groups, pool).runExecute(c, ctx)
        assertNull(r)
        verify(exactly = 1) { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `unparseable id returns null and emits 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(pathId = "not-a-uuid")
        val r = NodesRoute(groups, pool).runExecute(c, ctx)
        assertNull(r)
        verify(exactly = 1) { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `fabric8 list error propagates`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val client = mockk<KubernetesClient>()
        every { client.nodes().list() } throws RuntimeException("api down")
        coEvery { pool.get(id) } returns client
        assertFailsWith<RuntimeException> {
            NodesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        }
    }
}
