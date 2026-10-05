package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [CreateNamespaceRoute]. The route accepts a JSON body via
 * [bosca.server.ServerCall.receive], so we mock `request.contentType()`,
 * `request.bodyText()`, and `application.json` to deliver a working
 * deserialization path. Coverage:
 *   * Admin gate.
 *   * Cluster id parsing.
 *   * Body validation (blank `name` → 400).
 *   * Label coercion (booleans / numbers / null → string).
 *   * Round-tripping the fabric8 Namespace into the wire shape.
 */
@OptIn(ExperimentalUuidApi::class)
class CreateNamespaceRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun callWithBody(pathId: String?, body: String): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns if (pathId != null)
            Parameters.fromSingleValueMap(mapOf("id" to pathId))
        else Parameters.Empty
        every { call.request.contentType() } returns null
        coEvery { call.request.bodyText() } returns body
        every { call.application.json } returns Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
        return call
    }

    @Test
    fun `creates a namespace with the given labels`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val created = NamespaceBuilder()
            .withNewMetadata().withName("new-ns").addToLabels("team", "platform").endMetadata()
            .withNewStatus().withPhase("Active").endStatus()
            .build()
        val slot = slot<io.fabric8.kubernetes.api.model.Namespace>()
        every { client.namespaces().resource(capture(slot)).create() } answers { slot.captured }

        val r = CreateNamespaceRoute(groups, pool).runExecute(
            callWithBody(id.toString(), """{"name":"new-ns","labels":{"team":"platform","tier":3,"managed":true}}"""),
            ctx,
        )
        assertEquals("new-ns", r?.name)
        val labels = slot.captured.metadata?.labels
        assertEquals("platform", labels?.get("team"))
        assertEquals("3", labels?.get("tier"))
        assertEquals("true", labels?.get("managed"))
    }

    @Test
    fun `creates a namespace with no labels`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val slot = slot<io.fabric8.kubernetes.api.model.Namespace>()
        every { client.namespaces().resource(capture(slot)).create() } answers { slot.captured }

        val r = CreateNamespaceRoute(groups, pool).runExecute(
            callWithBody(id.toString(), """{"name":"plain"}"""), ctx,
        )
        assertEquals("plain", r?.name)
        assertTrue(slot.captured.metadata?.labels.isNullOrEmpty())
    }

    @Test
    fun `blank name returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = callWithBody(id.toString(), """{"name":""}""")
        assertNull(CreateNamespaceRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = callWithBody(null, """{"name":"x"}""")
        assertNull(CreateNamespaceRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `unparseable id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = callWithBody("not-a-uuid", """{"name":"x"}""")
        assertNull(CreateNamespaceRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin throws and never touches the pool`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            CreateNamespaceRoute(groups, pool).runExecute(
                callWithBody(UUID.random().toString(), """{"name":"x"}"""), ctx,
            )
        }
    }
}
