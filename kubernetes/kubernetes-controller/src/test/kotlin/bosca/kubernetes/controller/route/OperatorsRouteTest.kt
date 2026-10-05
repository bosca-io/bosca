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
 * Pins [OperatorsRoute]. Operators are grouped from the CRD list by
 * API group; built-in kubernetes groups are filtered out by the mapper,
 * so the route's job is to forward the CRD list plus a `(group → count)`
 * map. We exercise the namespaced / cluster-scoped count branch and the
 * graceful-degradation around individual CRD list failures.
 */
@OptIn(ExperimentalUuidApi::class)
class OperatorsRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(emptyMap())
        return call
    }

    private fun crd(group: String, kind: String, scope: String, version: String = "v1") =
        CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("$kind.$group").endMetadata()
            .withNewSpec()
                .withGroup(group)
                .withScope(scope)
                .withNewNames().withKind(kind).withPlural(kind.lowercase() + "s").endNames()
                .addToVersions(CustomResourceDefinitionVersionBuilder().withName(version).withServed(true).withStorage(true).build())
            .endSpec()
            .withNewStatus().addNewCondition().withType("Established").withStatus("True").endCondition().endStatus()
            .build()

    private fun gkr(kind: String, namespace: String?): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.metadata = ObjectMetaBuilder().withName("$kind-1").apply {
            if (namespace != null) withNamespace(namespace)
        }.build()
        r.kind = kind
        return r
    }

    @Test
    fun `operators groups CRDs and counts namespaced instances via inAnyNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(
            crd("cert-manager.io", "Certificate", "Namespaced"),
            crd("cert-manager.io", "Issuer", "Namespaced"),
        )
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList

        val d = client.stubGenericResources()
        d.forKind("Certificate").listInAnyNamespace(gkr("Certificate", "ns"), gkr("Certificate", "ns"))
        d.forKind("Issuer").listInAnyNamespace(gkr("Issuer", "ns"))

        val r = OperatorsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val op = r?.items?.single()
        assertEquals("cert-manager.io", op?.group)
        assertEquals(3, op?.instances)
        assertEquals(listOf("Certificate", "Issuer"), op?.kinds)
    }

    @Test
    fun `operators counts cluster-scoped CRDs via cluster list`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(crd("acme.io", "Widget", "Cluster"))
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList

        client.stubGenericResources().forKind("Widget").listClusterScoped(
            gkr("Widget", null), gkr("Widget", null),
        )

        val r = OperatorsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(2, r?.items?.single()?.instances)
    }

    @Test
    fun `operator instance count tolerates per-CRD list failures`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val crdList = CustomResourceDefinitionList()
        crdList.items = mutableListOf(crd("acme.io", "Widget", "Namespaced"))
        every { client.apiextensions().v1().customResourceDefinitions().list() } returns crdList
        client.stubGenericResources().forKind("Widget")
            .throwsOnInAnyNamespace(RuntimeException("rbac denied"))

        val r = OperatorsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.single()?.instances)
    }

    @Test
    fun `operators propagates errors from the CRD list itself`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.apiextensions().v1().customResourceDefinitions().list() } throws RuntimeException("boom")
        assertFailsWith<RuntimeException> {
            OperatorsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        }
        assertTrue(true)
    }
}
