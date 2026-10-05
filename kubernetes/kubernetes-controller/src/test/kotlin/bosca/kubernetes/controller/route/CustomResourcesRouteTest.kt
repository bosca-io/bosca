package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersionBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [CustomResourcesRoute]. The route walks every served CRD and
 * lists its instances, so we exercise the namespaced vs cluster-scoped
 * branches, the `group` and `namespace` query filters, and the
 * graceful-degradation of a per-CRD list failure (skipped, not fatal).
 */
@OptIn(ExperimentalUuidApi::class)
class CustomResourcesRouteTest {

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

    private fun crd(group: String, kind: String, scope: String, version: String = "v1") =
        CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("${kind.lowercase()}.$group").endMetadata()
            .withNewSpec()
                .withGroup(group)
                .withScope(scope)
                .withNewNames().withKind(kind).withPlural("${kind.lowercase()}s").endNames()
                .addToVersions(CustomResourceDefinitionVersionBuilder().withName(version).withServed(true).withStorage(true).build())
            .endSpec()
            .build()

    private fun gkr(kind: String, name: String, namespace: String?): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.metadata = ObjectMetaBuilder().withName(name).apply {
            if (namespace != null) withNamespace(namespace)
        }.build()
        r.kind = kind
        return r
    }

    @Test
    fun `walks every served CRD and aggregates instances`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(
            crd("acme.io", "Widget", "Namespaced"),
            crd("ops.io", "Gizmo", "Cluster"),
        )
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList
        val d = client.stubGenericResources()
        d.forKind("Widget").listInAnyNamespace(gkr("Widget", "w1", "ns"))
        d.forKind("Gizmo").listClusterScoped(gkr("Gizmo", "g1", null))

        val r = CustomResourcesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val kinds = r?.items?.map { it.kind }?.toSet()
        assertEquals(setOf("Widget", "Gizmo"), kinds)
    }

    @Test
    fun `group filter narrows the walk to a single API group`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(
            crd("acme.io", "Widget", "Namespaced"),
            crd("ops.io", "Gizmo", "Namespaced"),
        )
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList
        client.stubGenericResources().forKind("Widget").listInAnyNamespace(gkr("Widget", "w1", "ns"))

        val r = CustomResourcesRoute(groups, pool)
            .runExecute(call(id.toString(), mapOf("group" to "acme.io")), ctx)
        assertEquals(listOf("Widget"), r?.items?.map { it.kind })
    }

    @Test
    fun `namespace filter routes through inNamespace for namespaced CRDs`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(crd("acme.io", "Widget", "Namespaced"))
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList
        client.stubGenericResources().forKind("Widget").listInNamespace("prod", gkr("Widget", "w1", "prod"))

        val r = CustomResourcesRoute(groups, pool)
            .runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        assertEquals("prod", r?.items?.single()?.namespace)
    }

    @Test
    fun `per-CRD list failure is skipped, not fatal`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(
            crd("acme.io", "Widget", "Namespaced"),
            crd("ops.io", "Gizmo", "Namespaced"),
        )
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList
        val d = client.stubGenericResources()
        d.forKind("Widget").throwsOnInAnyNamespace(RuntimeException("rbac denied"))
        d.forKind("Gizmo").listInAnyNamespace(gkr("Gizmo", "g1", "ns"))

        val r = CustomResourcesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(listOf("Gizmo"), r?.items?.map { it.kind })
    }

    @Test
    fun `CRD list failure propagates`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.apiextensions().v1().customResourceDefinitions().list() } throws RuntimeException("api down")
        assertFailsWith<RuntimeException> {
            CustomResourcesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        }
        assertTrue(true)
    }
}
