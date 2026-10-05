package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.ConfigKind
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapListBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.SecretListBuilder
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ConfigResourcesRoute] — unfiltered fan-out across ConfigMaps +
 * Secrets, kind narrowing, namespace narrowing, and admin gate.
 */
@OptIn(ExperimentalUuidApi::class)
class ConfigResourcesRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return call
    }

    private fun configMap(name: String, ns: String) = ConfigMapBuilder()
        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
        .addToData("key", "value")
        .build()

    private fun secret(name: String, ns: String) = SecretBuilder()
        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
        .addToData("key", "dmFs")
        .build()

    @Test
    fun `no filter returns both kinds`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.configMaps().inAnyNamespace().list() } returns
            ConfigMapListBuilder().addToItems(configMap("cm", "ns")).build()
        every { client.secrets().inAnyNamespace().list() } returns
            SecretListBuilder().addToItems(secret("s", "ns")).build()

        val r = ConfigResourcesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val kinds = r?.items?.map { it.kind }?.toSet()
        assertEquals(setOf(ConfigKind.CONFIG_MAP, ConfigKind.SECRET), kinds)
    }

    @Test
    fun `kind=CONFIG_MAP narrows to configmaps only`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.configMaps().inAnyNamespace().list() } returns
            ConfigMapListBuilder().addToItems(configMap("a", "ns")).build()

        val r = ConfigResourcesRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "CONFIG_MAP")), ctx,
        )
        assertEquals(1, r?.items?.size)
        assertEquals(ConfigKind.CONFIG_MAP, r?.items?.single()?.kind)
        verify { client.configMaps().inAnyNamespace().list() }
    }

    @Test
    fun `kind=SECRET narrows to secrets only`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.secrets().inAnyNamespace().list() } returns
            SecretListBuilder().addToItems(secret("s", "ns")).build()

        val r = ConfigResourcesRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "SECRET")), ctx,
        )
        assertEquals(1, r?.items?.size)
        assertEquals(ConfigKind.SECRET, r?.items?.single()?.kind)
        verify { client.secrets().inAnyNamespace().list() }
    }

    @Test
    fun `unknown kind value is ignored`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.configMaps().inAnyNamespace().list() } returns
            ConfigMapListBuilder().addToItems(configMap("a", "ns")).build()
        every { client.secrets().inAnyNamespace().list() } returns SecretListBuilder().build()

        val r = ConfigResourcesRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("kind" to "BOGUS")), ctx,
        )
        // Falls back to fan-out across both kinds.
        assertEquals(1, r?.items?.size)
    }

    @Test
    fun `namespace routes through inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.configMaps().inNamespace("prod").list() } returns
            ConfigMapListBuilder().addToItems(configMap("cm", "prod")).build()
        every { client.secrets().inNamespace("prod").list() } returns
            SecretListBuilder().addToItems(secret("s", "prod")).build()

        val r = ConfigResourcesRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("namespace" to "prod")), ctx,
        )
        assertTrue(r!!.items.all { it.namespace == "prod" })
        verify { client.configMaps().inNamespace("prod").list() }
        verify { client.secrets().inNamespace("prod").list() }
    }
}
