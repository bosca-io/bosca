package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.gatewayapi.v1.Gateway
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClass
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClassBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayClassListBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayListBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteListBuilder
import io.fabric8.kubernetes.api.model.gatewayapi.v1.ParentReferenceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
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
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [GatewayClassesRoute], [GatewaysRoute], [HttpRoutesRoute]:
 * admin gate, fabric8 typed-model listing, namespace narrowing, the
 * cross-Gateway HTTPRoute count stitching in [GatewaysRoute], and the
 * "CRD not installed → empty list" graceful-degradation.
 */
@OptIn(ExperimentalUuidApi::class)
class GatewayApiFullRoutesTest {

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

    private fun crdNotFound(): KubernetesClientException =
        mockk<KubernetesClientException>().also { every { it.code } returns 404 }

    @Test
    fun `gatewayclasses returns mapped list`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(GatewayClass::class.java).list() } returns GatewayClassListBuilder()
            .addToItems(
                GatewayClassBuilder()
                    .withNewMetadata().withName("istio").endMetadata()
                    .withNewSpec().withControllerName("istio.io/gateway-controller").endSpec()
                    .build()
            )
            .build()
        val r = GatewayClassesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals("istio", r?.items?.single()?.name)
    }

    @Test
    fun `gatewayclasses returns empty when CRD missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(GatewayClass::class.java).list() } throws crdNotFound()
        val r = GatewayClassesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `gatewayclasses propagates non-404 errors`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(GatewayClass::class.java).list() } throws RuntimeException("network")
        assertFailsWith<RuntimeException> {
            GatewayClassesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        }
    }

    @Test
    fun `gateways stitches HTTPRoute counts via a single scan`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(Gateway::class.java).inAnyNamespace().list() } returns GatewayListBuilder()
            .addToItems(
                GatewayBuilder()
                    .withNewMetadata().withName("gw").withNamespace("prod").endMetadata()
                    .withNewSpec().withGatewayClassName("istio").endSpec()
                    .build()
            )
            .build()
        every { client.resources(HTTPRoute::class.java).inAnyNamespace().list() } returns HTTPRouteListBuilder()
            .addToItems(
                HTTPRouteBuilder()
                    .withNewMetadata().withName("r1").withNamespace("prod").endMetadata()
                    .withNewSpec()
                        .addToParentRefs(ParentReferenceBuilder().withName("gw").withNamespace("prod").build())
                    .endSpec()
                    .build()
            )
            .addToItems(
                HTTPRouteBuilder()
                    .withNewMetadata().withName("r2").withNamespace("prod").endMetadata()
                    .withNewSpec()
                        .addToParentRefs(ParentReferenceBuilder().withName("gw").withNamespace("prod").build())
                    .endSpec()
                    .build()
            )
            .build()
        val r = GatewaysRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(2, r?.items?.single()?.routes)
    }

    @Test
    fun `gateways route through inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(Gateway::class.java).inNamespace("prod").list() } returns GatewayListBuilder()
            .addToItems(
                GatewayBuilder()
                    .withNewMetadata().withName("gw").withNamespace("prod").endMetadata()
                    .withNewSpec().withGatewayClassName("istio").endSpec()
                    .build()
            )
            .build()
        every { client.resources(HTTPRoute::class.java).inNamespace("prod").list() } returns
            HTTPRouteListBuilder().build()
        GatewaysRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        verify { client.resources(Gateway::class.java).inNamespace("prod").list() }
    }

    @Test
    fun `gateways returns empty when CRD missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(Gateway::class.java).inAnyNamespace().list() } throws crdNotFound()
        val r = GatewaysRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `httproutes returns mapped list`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(HTTPRoute::class.java).inAnyNamespace().list() } returns HTTPRouteListBuilder()
            .addToItems(
                HTTPRouteBuilder()
                    .withNewMetadata().withName("r1").withNamespace("ns").endMetadata().build()
            )
            .build()
        val r = HttpRoutesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(listOf("r1"), r?.items?.map { it.name })
    }

    @Test
    fun `httproutes returns empty when CRD missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(HTTPRoute::class.java).inAnyNamespace().list() } throws crdNotFound()
        val r = HttpRoutesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `httproutes narrow via inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.resources(HTTPRoute::class.java).inNamespace("prod").list() } returns HTTPRouteListBuilder()
            .addToItems(HTTPRouteBuilder().withNewMetadata().withName("r").withNamespace("prod").endMetadata().build())
            .build()
        HttpRoutesRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        verify { client.resources(HTTPRoute::class.java).inNamespace("prod").list() }
    }

    @Test
    fun `all three routes deny non-admin callers`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            GatewayClassesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
        assertFailsWith<SecurityException> {
            GatewaysRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
        assertFailsWith<SecurityException> {
            HttpRoutesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
    }
}
