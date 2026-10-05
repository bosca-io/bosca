package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
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
 * Pins [CertManagerCertificatesRoute] and [CertManagerIssuersRoute] —
 * cert-manager is optional in the cluster, so missing CRDs short-circuit
 * to an empty response rather than throwing. We also assert the
 * cluster-wide Certificate scan that drives the per-issuer count.
 */
@OptIn(ExperimentalUuidApi::class)
class CertManagerRoutesTest {

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

    private fun resource(
        kindName: String,
        name: String,
        namespace: String? = null,
        spec: Map<String, Any?>? = null,
        status: Map<String, Any?>? = null,
    ): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.metadata = ObjectMetaBuilder().withName(name).apply {
            if (namespace != null) withNamespace(namespace)
        }.build()
        r.kind = kindName
        r.apiVersion = "cert-manager.io/v1"
        if (spec != null) r.additionalProperties["spec"] = spec
        if (status != null) r.additionalProperties["status"] = status
        return r
    }

    @Test
    fun `certificates returns mapped list when CRD is present`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        client.stubGenericResources().forKind("Certificate").listInAnyNamespace(
            resource(
                "Certificate", "api-cert", "prod",
                spec = mapOf(
                    "dnsNames" to listOf("api.example.com"),
                    "issuerRef" to mapOf("name" to "letsencrypt", "kind" to "ClusterIssuer"),
                    "secretName" to "api-cert-tls",
                ),
                status = mapOf("conditions" to listOf(mapOf("type" to "Ready", "status" to "True"))),
            ),
        )

        val r = CertManagerCertificatesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(1, r?.items?.size)
        assertEquals("api-cert", r?.items?.single()?.name)
    }

    @Test
    fun `certificates returns empty list when CRD is missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        client.stubGenericResources().forKind("Certificate")
            .throwsOnInAnyNamespace(RuntimeException("Not Found"))

        val r = CertManagerCertificatesRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `certificates narrow via inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        client.stubGenericResources().forKind("Certificate").listInNamespace(
            "prod",
            resource(
                "Certificate", "api-cert", "prod",
                spec = mapOf("dnsNames" to listOf("api.example.com"), "issuerRef" to mapOf("name" to "i")),
            ),
        )

        val r = CertManagerCertificatesRoute(groups, pool)
            .runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        assertEquals("prod", r?.items?.single()?.namespace)
    }

    @Test
    fun `issuers returns mapped list when CRD is present`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Certificate").listInAnyNamespace(
            resource(
                "Certificate", "c1", "prod",
                spec = mapOf("issuerRef" to mapOf("name" to "letsencrypt", "kind" to "ClusterIssuer")),
            ),
        )
        d.forKind("Issuer").listInAnyNamespace(
            resource("Issuer", "my-issuer", "prod", spec = mapOf("ca" to mapOf("secretName" to "s"))),
        )
        d.forKind("ClusterIssuer").listClusterScoped(
            resource(
                "ClusterIssuer", "letsencrypt", null,
                spec = mapOf("acme" to mapOf("server" to "https://acme.example/dir")),
            ),
        )

        val r = CertManagerIssuersRoute(groups, pool).runExecute(call(id.toString()), ctx)
        val names = r?.items?.map { it.name }?.toSet()
        assertEquals(setOf("my-issuer", "letsencrypt"), names)
        val le = r?.items?.firstOrNull { it.name == "letsencrypt" }
        assertEquals(1, le?.certs)
    }

    @Test
    fun `issuers narrow via inNamespace skips ClusterIssuer`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Certificate").listInAnyNamespace()
        d.forKind("Issuer").listInNamespace(
            "prod",
            resource("Issuer", "i", "prod", spec = mapOf("ca" to mapOf("secretName" to "s"))),
        )
        val r = CertManagerIssuersRoute(groups, pool)
            .runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        assertEquals(listOf("i"), r?.items?.map { it.name })
    }

    @Test
    fun `issuers returns empty when no CRDs are installed`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val d = client.stubGenericResources()
        d.forKind("Certificate").throwsOnInAnyNamespace(RuntimeException("no CRD"))
        d.forKind("Issuer").throwsOnInAnyNamespace(RuntimeException("no CRD"))
        d.forKind("ClusterIssuer").throwsOnClusterList(RuntimeException("no CRD"))
        val r = CertManagerIssuersRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `non-admin throws for both routes`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            CertManagerCertificatesRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
        assertFailsWith<SecurityException> {
            CertManagerIssuersRoute(groups, pool).runExecute(call(UUID.random().toString()), ctx)
        }
        assertTrue(true)
    }
}
