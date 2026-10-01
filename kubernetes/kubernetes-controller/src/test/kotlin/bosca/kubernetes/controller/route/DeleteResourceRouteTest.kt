package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.StatusDetails
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
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
 * Pins [DeleteResourceRoute]. Coverage:
 *   * Admin gate, cluster id, required `kind` + `name` query params.
 *   * Namespaced deletion (`namespace` query routes through inNamespace).
 *   * Cluster-scoped deletion (no `namespace` query).
 *   * `deleted=true` when fabric8 returns a status; `deleted=false` when
 *     it returns an empty list (no resource matched).
 *   * Errors are caught and reported as `deleted=false, details=<msg>`
 *     — the route is admin-facing, so the user gets a readable failure.
 */
@OptIn(ExperimentalUuidApi::class)
class DeleteResourceRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String?, query: Map<String, String>): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns if (pathId != null)
            Parameters.fromSingleValueMap(mapOf("id" to pathId))
        else Parameters.Empty
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    @Test
    fun `namespaced delete succeeds when fabric8 returns a status`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inNamespace("prod").withName("api").delete()
        } returns listOf(mockk<StatusDetails>(relaxed = true))
        val r = DeleteResourceRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "Pod", "name" to "api", "namespace" to "prod")), ctx,
        )
        assertEquals(true, r?.deleted)
    }

    @Test
    fun `cluster-scoped delete uses the non-namespaced path`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .withName("nodes-1").delete()
        } returns listOf(mockk<StatusDetails>(relaxed = true))
        val r = DeleteResourceRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "Node", "name" to "nodes-1")), ctx,
        )
        assertEquals(true, r?.deleted)
        verify {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .withName("nodes-1").delete()
        }
    }

    @Test
    fun `empty status list reports not-deleted`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inNamespace("prod").withName("missing").delete()
        } returns emptyList()
        val r = DeleteResourceRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "Pod", "name" to "missing", "namespace" to "prod")), ctx,
        )
        assertEquals(false, r?.deleted)
        assertEquals("no resource matched", r?.details)
    }

    @Test
    fun `fabric8 errors are wrapped in a not-deleted response`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every {
            client.genericKubernetesResources(any<ResourceDefinitionContext>())
                .inNamespace("prod").withName("api").delete()
        } throws RuntimeException("forbidden")
        val r = DeleteResourceRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "Pod", "name" to "api", "namespace" to "prod")), ctx,
        )
        assertEquals(false, r?.deleted)
        assertTrue(r?.details?.contains("forbidden") ?: false)
    }

    @Test
    fun `missing kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(UUID.random().toString(), mapOf("name" to "api"))
        assertNull(DeleteResourceRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing name returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(UUID.random().toString(), mapOf("kind" to "Pod"))
        assertNull(DeleteResourceRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(null, mapOf("kind" to "Pod", "name" to "api"))
        assertNull(DeleteResourceRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }
}
