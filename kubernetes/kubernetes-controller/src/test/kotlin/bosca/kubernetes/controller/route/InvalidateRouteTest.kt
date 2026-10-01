package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [InvalidateRoute]. The route is a 5-line trampoline but it sits
 * on the trust boundary — only admins can pop a cluster's cached
 * fabric8 client, otherwise a non-admin could DoS the controller's
 * connection pool by repeatedly invalidating live clusters.
 *
 *   * Admin gate fails fast: the route delegates to
 *     [GroupEvaluator.verifyHasAdminGroup], which throws on miss; we
 *     verify the pool is *not* touched in that case.
 *   * Missing or unparseable `{id}` path parameter → 400 and the pool
 *     is left alone.
 *   * Happy path returns `{ok: true}` and calls
 *     [ClusterClientPool.invalidate] exactly once with the parsed id.
 */
@OptIn(ExperimentalUuidApi::class)
class InvalidateRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>(relaxed = true)

    private fun route() = InvalidateRoute(groups, pool)

    private fun stubCall(pathId: String? = null): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        val params = if (pathId != null) {
            Parameters.fromSingleValueMap(mapOf("id" to pathId))
        } else {
            Parameters.Empty
        }
        every { call.pathParameters } returns params
        return call
    }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    @Test
    fun `happy path invalidates the cluster and returns ok=true`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val call = stubCall(pathId = id.toString())
        val ctx = mockk<AuthenticationContext>()

        val response = route().runExecute(call, ctx)

        assertTrue(response?.ok == true)
        verify(exactly = 1) { pool.invalidate(id) }
    }

    @Test
    fun `non-admin caller short-circuits before touching the pool`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("not admin")
        val call = stubCall(pathId = UUID.random().toString())
        val ctx = mockk<AuthenticationContext>()

        assertFailsWith<SecurityException> { route().runExecute(call, ctx) }
        verify(exactly = 0) { pool.invalidate(any()) }
    }

    @Test
    fun `missing id path parameter returns null and emits 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val call = stubCall(pathId = null)
        val ctx = mockk<AuthenticationContext>()

        val response = route().runExecute(call, ctx)
        assertNull(response)
        verify(exactly = 1) { call.respond(HttpStatusCode.BadRequest) }
        verify(exactly = 0) { pool.invalidate(any()) }
    }

    @Test
    fun `unparseable id returns null and emits 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val call = stubCall(pathId = "not-a-uuid")
        val ctx = mockk<AuthenticationContext>()

        val response = route().runExecute(call, ctx)
        assertNull(response)
        verify(exactly = 1) { call.respond(HttpStatusCode.BadRequest) }
        verify(exactly = 0) { pool.invalidate(any()) }
    }

    @Test
    fun `serializer returns the wire shape serializer`() {
        // Route.serializer() is protected; access it reflectively for the assertion.
        val method = InvalidateRoute::class.java.superclass.getDeclaredMethod("serializer").apply { isAccessible = true }
        val s = method.invoke(route()) as? kotlinx.serialization.KSerializer<*>
            ?: fail("serializer must not be null")
        assertEquals("bosca.kubernetes.controller.route.InvalidateResponse", s.descriptor.serialName)
    }
}
