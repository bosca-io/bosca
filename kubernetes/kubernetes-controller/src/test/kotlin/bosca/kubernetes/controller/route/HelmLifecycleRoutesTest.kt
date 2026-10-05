package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.helm.HelmRuntime
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.K8sHelmRelease
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [HelmRollbackRoute] and [HelmUninstallRoute]. The install /
 * upgrade routes call into Postgres via `withConnectionManager`, which
 * needs a real connection pool — coverage for those lives in the
 * downstream HelmRuntime tests.
 */
@OptIn(ExperimentalUuidApi::class)
class HelmLifecycleRoutesTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val helm = mockk<HelmRuntime>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>(relaxed = true)

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun callWithBody(pathId: String, body: String, path: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(
            mapOf("id" to pathId) + path
        )
        every { c.request.contentType() } returns null
        coEvery { c.request.bodyText() } returns body
        every { c.application.json } returns Json { ignoreUnknownKeys = true; explicitNulls = false }
        return c
    }

    private fun pathOnly(path: Map<String, String>, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    private fun release(name: String, ns: String, revision: Int) = K8sHelmRelease(
        id = "$ns/$name/$revision", name = name, namespace = ns,
        chart = "nginx", chartVersion = "1.0.0", appVersion = "1.27.0",
        revision = revision, status = HelmStatus.DEPLOYED, updated = "now", installed = "1d",
        repo = "", repoUrl = "", description = "",
    )

    @Test
    fun `rollback forwards the request to HelmRuntime`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { helm.rollback(client, id, any()) } returns release("api", "prod", 4)
        val r = HelmRollbackRoute(groups, pool, helm).runExecute(
            callWithBody(id.toString(), """{"name":"api","namespace":"prod","toRevision":3}"""), ctx,
        )
        assertEquals(4, r?.revision)
    }

    @Test
    fun `rollback rejects empty name or namespace or non-positive revision`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c1 = callWithBody(id.toString(), """{"name":"","namespace":"prod","toRevision":1}""")
        assertNull(HelmRollbackRoute(groups, pool, helm).runExecute(c1, ctx))
        verify { c1.respond(HttpStatusCode.BadRequest) }

        val c2 = callWithBody(id.toString(), """{"name":"x","namespace":"","toRevision":1}""")
        assertNull(HelmRollbackRoute(groups, pool, helm).runExecute(c2, ctx))
        verify { c2.respond(HttpStatusCode.BadRequest) }

        val c3 = callWithBody(id.toString(), """{"name":"x","namespace":"prod","toRevision":0}""")
        assertNull(HelmRollbackRoute(groups, pool, helm).runExecute(c3, ctx))
        verify { c3.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `rollback propagates HelmRuntime errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        coEvery { helm.rollback(client, id, any()) } throws RuntimeException("no release")
        assertFailsWith<RuntimeException> {
            HelmRollbackRoute(groups, pool, helm).runExecute(
                callWithBody(id.toString(), """{"name":"x","namespace":"prod","toRevision":1}"""), ctx,
            )
        }
    }

    @Test
    fun `uninstall forwards namespace and name and defaults keepHistory to false`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.uninstall(id, "prod", "api", false) } returns true
        val r = HelmUninstallRoute(groups, helm).runExecute(
            pathOnly(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api")), ctx,
        )
        assertEquals(true, r)
        coVerify { helm.uninstall(id, "prod", "api", false) }
    }

    @Test
    fun `uninstall forwards keepHistory=true query parameter`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.uninstall(id, "prod", "api", true) } returns true
        HelmUninstallRoute(groups, helm).runExecute(
            pathOnly(
                mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"),
                mapOf("keepHistory" to "true"),
            ), ctx,
        )
        coVerify { helm.uninstall(id, "prod", "api", true) }
    }

    @Test
    fun `uninstall returns 400 when namespace or name missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        val c = pathOnly(mapOf("id" to id.toString(), "name" to "api"))
        assertNull(HelmUninstallRoute(groups, helm).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `uninstall propagates HelmRuntime errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { helm.uninstall(id, "prod", "api", false) } throws RuntimeException("not found")
        assertFailsWith<RuntimeException> {
            HelmUninstallRoute(groups, helm).runExecute(
                pathOnly(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api")), ctx,
            )
        }
    }

    @Test
    fun `non-admin caller is rejected on both routes`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            HelmRollbackRoute(groups, pool, helm).runExecute(
                callWithBody(UUID.random().toString(), """{"name":"x","namespace":"y","toRevision":1}"""), ctx,
            )
        }
        assertFailsWith<SecurityException> {
            HelmUninstallRoute(groups, helm).runExecute(
                pathOnly(mapOf("id" to UUID.random().toString(), "namespace" to "n", "name" to "x")), ctx,
            )
        }
    }
}
