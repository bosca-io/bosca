package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.SECRET_VALUE_REDACTED
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ConfigEntriesRoute] — admin gate, path parameters (kind /
 * namespace / name), Secret value masking, NotFound when the resource is
 * missing, and BadRequest for malformed `kind` or missing path bits.
 */
@OptIn(ExperimentalUuidApi::class)
class ConfigEntriesRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>(relaxed = true)
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(path)
        return call
    }

    @Test
    fun `configmap entries are returned as plain values`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.configMaps().inNamespace("default").withName("cm").get() } returns ConfigMapBuilder()
            .withNewMetadata().withName("cm").withNamespace("default").endMetadata()
            .addToData("key", "hello")
            .build()

        val r = ConfigEntriesRoute(groups, pool).runExecute(
            call(mapOf("id" to id.toString(), "kind" to "CONFIG_MAP", "namespace" to "default", "name" to "cm")),
            ctx,
        )
        assertEquals("hello", r?.items?.single()?.value)
    }

    @Test
    fun `secret values are masked, never returned`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val encoded = Base64.getEncoder().encodeToString("super-secret".toByteArray())
        every { client.secrets().inNamespace("prod").withName("token").get() } returns SecretBuilder()
            .withNewMetadata().withName("token").withNamespace("prod").endMetadata()
            .addToData("api", encoded)
            .build()

        val r = ConfigEntriesRoute(groups, pool).runExecute(
            call(mapOf("id" to id.toString(), "kind" to "SECRET", "namespace" to "prod", "name" to "token")),
            ctx,
        )
        val entry = r!!.items.single()
        assertEquals("api", entry.key)
        assertEquals(SECRET_VALUE_REDACTED, entry.value)
        // The cleartext (and its base64 wire form) must never appear.
        assertTrue(r.items.none { it.value.contains("super-secret") || it.value.contains(encoded) })
    }

    @Test
    fun `secret binary values are masked too`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val encoded = Base64.getEncoder().encodeToString(binary)
        every { client.secrets().inNamespace("prod").withName("tls").get() } returns SecretBuilder()
            .withNewMetadata().withName("tls").withNamespace("prod").endMetadata()
            .addToData("key", encoded)
            .build()

        val r = ConfigEntriesRoute(groups, pool).runExecute(
            call(mapOf("id" to id.toString(), "kind" to "SECRET", "namespace" to "prod", "name" to "tls")),
            ctx,
        )
        assertEquals(SECRET_VALUE_REDACTED, r!!.items.single().value)
    }

    @Test
    fun `missing kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "namespace" to "ns", "name" to "n"))
        assertNull(ConfigEntriesRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `invalid kind value returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "kind" to "BOGUS", "namespace" to "ns", "name" to "n"))
        assertNull(ConfigEntriesRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing namespace returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "kind" to "CONFIG_MAP", "name" to "n"))
        assertNull(ConfigEntriesRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing name returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "kind" to "CONFIG_MAP", "namespace" to "ns"))
        assertNull(ConfigEntriesRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `not found configmap emits 404`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.configMaps().inNamespace("ns").withName("nope").get() } returns null
        val c = call(mapOf("id" to id.toString(), "kind" to "CONFIG_MAP", "namespace" to "ns", "name" to "nope"))
        assertNull(ConfigEntriesRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.NotFound) }
    }

    @Test
    fun `not found secret emits 404`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.secrets().inNamespace("ns").withName("nope").get() } returns null
        val c = call(mapOf("id" to id.toString(), "kind" to "SECRET", "namespace" to "ns", "name" to "nope"))
        assertNull(ConfigEntriesRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.NotFound) }
    }
}
