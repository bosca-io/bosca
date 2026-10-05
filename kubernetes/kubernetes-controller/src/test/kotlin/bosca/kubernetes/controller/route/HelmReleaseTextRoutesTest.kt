package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.helm.HelmRuntime
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
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
 * Pins [HelmReleaseValuesRoute] and [HelmReleaseManifestRoute]. Both
 * are thin admin-gated wrappers around the [HelmRuntime] — the route's
 * job is to forward the (cluster, namespace, name, revision) tuple and
 * propagate runtime errors instead of swallowing them.
 */
@OptIn(ExperimentalUuidApi::class)
class HelmReleaseTextRoutesTest {

    private val groups = mockk<GroupEvaluator>()
    private val helm = mockk<HelmRuntime>()
    private val ctx = mockk<AuthenticationContext>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    @Test
    fun `values route forwards to HelmRuntime getValues with current revision`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.getValues(id, "prod", "api", null) } returns "replicas: 3\n"
        val r = HelmReleaseValuesRoute(groups, helm).runExecute(
            call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api")), ctx,
        )
        assertEquals("replicas: 3\n", r?.yaml)
    }

    @Test
    fun `values route forwards revision query parameter when set`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.getValues(id, "prod", "api", 4) } returns "replicas: 2\n"
        val r = HelmReleaseValuesRoute(groups, helm).runExecute(
            call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"), mapOf("revision" to "4")),
            ctx,
        )
        assertEquals("replicas: 2\n", r?.yaml)
    }

    @Test
    fun `values route ignores non-numeric revision`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.getValues(id, "prod", "api", null) } returns ""
        HelmReleaseValuesRoute(groups, helm).runExecute(
            call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"), mapOf("revision" to "abc")),
            ctx,
        )
        io.mockk.coVerify { helm.getValues(id, "prod", "api", null) }
    }

    @Test
    fun `values route returns 400 when namespace or name is missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c1 = call(mapOf("id" to UUID.random().toString(), "name" to "api"))
        assertNull(HelmReleaseValuesRoute(groups, helm).runExecute(c1, ctx))
        verify { c1.respond(HttpStatusCode.BadRequest) }

        val c2 = call(mapOf("id" to UUID.random().toString(), "namespace" to "prod"))
        assertNull(HelmReleaseValuesRoute(groups, helm).runExecute(c2, ctx))
        verify { c2.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `values route propagates HelmRuntime errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.getValues(id, "prod", "api", null) } throws RuntimeException("no release")
        assertFailsWith<RuntimeException> {
            HelmReleaseValuesRoute(groups, helm).runExecute(
                call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api")), ctx,
            )
        }
    }

    @Test
    fun `manifest route forwards to HelmRuntime getManifest`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.getManifest(id, "prod", "api", 2) } returns "kind: Deployment\n"
        val r = HelmReleaseManifestRoute(groups, helm).runExecute(
            call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"), mapOf("revision" to "2")),
            ctx,
        )
        assertEquals("kind: Deployment\n", r?.yaml)
    }

    @Test
    fun `manifest route returns 400 when path parameters are missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "namespace" to "prod"))
        assertNull(HelmReleaseManifestRoute(groups, helm).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `manifest route propagates HelmRuntime errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.getManifest(id, "prod", "api", null) } throws RuntimeException("no release")
        assertFailsWith<RuntimeException> {
            HelmReleaseManifestRoute(groups, helm).runExecute(
                call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api")), ctx,
            )
        }
    }

    @Test
    fun `non-admin is rejected on both routes`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            HelmReleaseValuesRoute(groups, helm).runExecute(
                call(mapOf("id" to UUID.random().toString(), "namespace" to "n", "name" to "x")), ctx,
            )
        }
        assertFailsWith<SecurityException> {
            HelmReleaseManifestRoute(groups, helm).runExecute(
                call(mapOf("id" to UUID.random().toString(), "namespace" to "n", "name" to "x")), ctx,
            )
        }
    }

}
