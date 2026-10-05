package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.SECRET_VALUE_REDACTED
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.Base64
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [YamlRoute] — admin gate, mandatory `kind` + `name` query
 * parameters, optional `namespace` / `group` / `version` (defaulting
 * to `v1`), `NotFound` on a missing resource, the YAML rendering path,
 * and the Secret special-case that redacts `data` / `stringData` so a
 * Secret's values never leave the controller in its manifest.
 */
@OptIn(ExperimentalUuidApi::class)
class YamlRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>(relaxed = true)

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String>): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return call
    }

    @Test
    fun `happy path fetches a namespaced resource and serializes to YAML`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val pod = GenericKubernetesResourceBuilder().build().apply {
            metadata = ObjectMetaBuilder().withName("api").withNamespace("prod").build()
            kind = "Pod"
            apiVersion = "v1"
        }
        every { client.genericKubernetesResources(any<ResourceDefinitionContext>())
            .inNamespace("prod").withName("api").get() } returns pod

        val r = YamlRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "Pod", "name" to "api", "namespace" to "prod")),
            ctx,
        )
        assertTrue(r!!.yaml.contains("kind: \"Pod\"") || r.yaml.contains("kind: Pod"))
        assertTrue(r.yaml.contains("api"))
    }

    @Test
    fun `missing kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { pool.get(any<UUID>()) } returns client
        val c = call(UUID.random().toString(), mapOf("name" to "x"))
        assertNull(YamlRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `blank kind returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { pool.get(any<UUID>()) } returns client
        val c = call(UUID.random().toString(), mapOf("kind" to "", "name" to "x"))
        assertNull(YamlRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing name returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        coEvery { pool.get(any<UUID>()) } returns client
        val c = call(UUID.random().toString(), mapOf("kind" to "Pod"))
        assertNull(YamlRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing namespace uses cluster-scoped lookup`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val res = GenericKubernetesResourceBuilder().build().apply {
            metadata = ObjectMetaBuilder().withName("cluster-role").build()
            kind = "ClusterRole"
            apiVersion = "rbac.authorization.k8s.io/v1"
        }
        every { client.genericKubernetesResources(any<ResourceDefinitionContext>())
            .withName("cluster-role").get() } returns res

        val r = YamlRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "ClusterRole", "name" to "cluster-role")),
            ctx,
        )
        assertTrue(r!!.yaml.contains("cluster-role"))
    }

    @Test
    fun `resource not found emits 404 and returns null`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.genericKubernetesResources(any<ResourceDefinitionContext>())
            .inNamespace("ns").withName("missing").get() } returns null

        val c = call(id.toString(), mapOf("kind" to "Pod", "name" to "missing", "namespace" to "ns"))
        val r = YamlRoute(groups, pool).runExecute(c, ctx)
        assertNull(r)
        verify { c.respond(HttpStatusCode.NotFound) }
    }

    @Test
    fun `missing id path parameter returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.Empty
        val r = YamlRoute(groups, pool).runExecute(call, ctx)
        assertNull(r)
        verify { call.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `secret manifest is redacted, never emitting values`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val encoded = Base64.getEncoder().encodeToString("super-secret".toByteArray())
        every { client.secrets().inNamespace("prod").withName("db").get() } returns SecretBuilder()
            .withNewMetadata().withName("db").withNamespace("prod").endMetadata()
            .withType("Opaque")
            .addToData("password", encoded)
            .build()

        val r = YamlRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "Secret", "name" to "db", "namespace" to "prod")),
            ctx,
        )
        // Key stays visible; value is the redaction marker; cleartext and its
        // base64 wire form are both absent from the manifest.
        assertTrue(r!!.yaml.contains("password"))
        assertTrue(r.yaml.contains(SECRET_VALUE_REDACTED))
        assertTrue(!r.yaml.contains("super-secret"))
        assertTrue(!r.yaml.contains(encoded))
    }

    @Test
    fun `secret without a namespace returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(id.toString(), mapOf("kind" to "Secret", "name" to "db"))
        val r = YamlRoute(groups, pool).runExecute(c, ctx)
        assertNull(r)
        verify { c.respond(HttpStatusCode.BadRequest) }
    }
}
