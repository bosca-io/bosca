package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServiceListBuilder
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceListBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressListBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyListBuilder
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
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ServicesRoute], [IngressesRoute], [NetworkPoliciesRoute] — each
 * delegates to fabric8's typed builders and applies the optional
 * namespace narrowing.
 */
@OptIn(ExperimentalUuidApi::class)
class NetworkingRoutesTest {

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

    @Test
    fun `services fan out when no namespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.services().inAnyNamespace().list() } returns ServiceListBuilder()
            .addToItems(
                ServiceBuilder()
                    .withNewMetadata().withName("api").withNamespace("a").endMetadata()
                    .withNewSpec().withType("ClusterIP").endSpec()
                    .build()
            )
            .build()
        // EndpointSlice list backs the per-service endpoint count.
        // Empty list is fine for these tests — they assert the
        // service name lands, not the count value.
        every { client.discovery().v1().endpointSlices().inAnyNamespace().list() } returns
            EndpointSliceListBuilder().build()

        val r = ServicesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals("api", r?.items?.single()?.name)
    }

    @Test
    fun `services route through inNamespace when set`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.services().inNamespace("prod").list() } returns ServiceListBuilder()
            .addToItems(
                ServiceBuilder()
                    .withNewMetadata().withName("api").withNamespace("prod").endMetadata()
                    .withNewSpec().withType("ClusterIP").endSpec()
                    .build()
            )
            .build()
        every { client.discovery().v1().endpointSlices().inNamespace("prod").list() } returns
            EndpointSliceListBuilder().build()
        ServicesRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        verify { client.services().inNamespace("prod").list() }
    }

    @Test
    fun `ingresses fan out when no namespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.network().v1().ingresses().inAnyNamespace().list() } returns IngressListBuilder()
            .addToItems(
                IngressBuilder()
                    .withNewMetadata().withName("web").withNamespace("a").endMetadata()
                    .build()
            )
            .build()
        val r = IngressesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals("web", r?.items?.single()?.name)
    }

    @Test
    fun `ingresses narrow via inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.network().v1().ingresses().inNamespace("prod").list() } returns IngressListBuilder()
            .addToItems(IngressBuilder().withNewMetadata().withName("web").withNamespace("prod").endMetadata().build())
            .build()
        IngressesRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        verify { client.network().v1().ingresses().inNamespace("prod").list() }
    }

    @Test
    fun `networkpolicies fan out when no namespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.network().v1().networkPolicies().inAnyNamespace().list() } returns
            NetworkPolicyListBuilder()
                .addToItems(
                    NetworkPolicyBuilder()
                        .withNewMetadata().withName("deny-all").withNamespace("a").endMetadata()
                        .build()
                )
                .build()
        val r = NetworkPoliciesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals("deny-all", r?.items?.single()?.name)
    }

    @Test
    fun `networkpolicies narrow via inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.network().v1().networkPolicies().inNamespace("prod").list() } returns
            NetworkPolicyListBuilder()
                .addToItems(
                    NetworkPolicyBuilder()
                        .withNewMetadata().withName("deny-all").withNamespace("prod").endMetadata()
                        .build()
                )
                .build()
        NetworkPoliciesRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        verify { client.network().v1().networkPolicies().inNamespace("prod").list() }
    }

    @Test
    fun `services non-admin throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            ServicesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
    }

    @Test
    fun `ingresses non-admin throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            IngressesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
    }

    @Test
    fun `networkpolicies non-admin throws`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            NetworkPoliciesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
    }
}
