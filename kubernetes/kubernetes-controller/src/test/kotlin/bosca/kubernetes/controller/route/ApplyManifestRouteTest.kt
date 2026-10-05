package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [ApplyManifestRoute]. The route parses a YAML body via fabric8,
 * then issues server-side apply (or dry-run) per document. Coverage:
 *   * Admin gate + body validation.
 *   * Happy path returns `succeeded=true`, all docs in `applied`.
 *   * Partial failures: success + failure mix produces a row in each
 *     list and `succeeded=false`.
 *   * `dryRun=true` toggles the dry-run path on each resource handle.
 *   * Parse failures bubble up as a single `<parse>` failure row.
 */
@OptIn(ExperimentalUuidApi::class)
class ApplyManifestRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>(relaxed = true)

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, body: String): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { c.request.contentType() } returns null
        coEvery { c.request.bodyText() } returns body
        every { c.application.json } returns Json { ignoreUnknownKeys = true; explicitNulls = false }
        return c
    }

    private fun cm(name: String, ns: String) = ConfigMapBuilder()
        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
        .addToData("key", "value")
        .build()

    private fun setupLoad(items: List<HasMetadata>) {
        every { client.load(any()).items() } returns items
    }

    @Test
    fun `happy path applies every doc`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val docs = listOf(cm("a", "ns"), cm("b", "ns"))
        setupLoad(docs)

        val handle = mockk<io.fabric8.kubernetes.client.dsl.NamespaceableResource<HasMetadata>>(relaxed = true)
        every { client.resource(any<HasMetadata>()) } returns handle
        every { handle.fieldManager(any()).forceConflicts().serverSideApply() } returns mockk(relaxed = true)

        val r = ApplyManifestRoute(groups, pool).runExecute(
            call(id.toString(), """{"manifest":"---\nkind: ConfigMap","dryRun":false}"""), ctx,
        )
        assertEquals(true, r?.succeeded)
        assertEquals(setOf("ConfigMap/ns/a", "ConfigMap/ns/b"), r?.applied?.toSet())
        assertTrue(r?.failed?.isEmpty() ?: false)
        assertNull(r?.dryRun)
    }

    @Test
    fun `partial failure records both lists`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val doc1 = cm("a", "ns")
        val doc2 = cm("b", "ns")
        setupLoad(listOf(doc1, doc2))

        val ok = mockk<io.fabric8.kubernetes.client.dsl.NamespaceableResource<HasMetadata>>(relaxed = true)
        val bad = mockk<io.fabric8.kubernetes.client.dsl.NamespaceableResource<HasMetadata>>(relaxed = true)
        every { client.resource(doc1 as HasMetadata) } returns ok
        every { client.resource(doc2 as HasMetadata) } returns bad
        every { ok.fieldManager(any()).forceConflicts().serverSideApply() } returns mockk(relaxed = true)
        every { bad.fieldManager(any()).forceConflicts().serverSideApply() } throws RuntimeException("conflict")

        val r = ApplyManifestRoute(groups, pool).runExecute(
            call(id.toString(), """{"manifest":"---\nkind: ConfigMap"}"""), ctx,
        )
        assertEquals(false, r?.succeeded)
        assertEquals(listOf("ConfigMap/ns/a"), r?.applied)
        assertEquals(listOf("ConfigMap/ns/b"), r?.failed?.map { it.resource })
    }

    @Test
    fun `dryRun=true routes through dryRun handle and produces a summary`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        setupLoad(listOf(cm("a", "ns")))

        val handle = mockk<io.fabric8.kubernetes.client.dsl.NamespaceableResource<HasMetadata>>(relaxed = true)
        every { client.resource(any<HasMetadata>()) } returns handle
        every { handle.dryRun().fieldManager(any()).forceConflicts().serverSideApply() } returns mockk(relaxed = true)

        val r = ApplyManifestRoute(groups, pool).runExecute(
            call(id.toString(), """{"manifest":"---\nkind: ConfigMap","dryRun":true}"""), ctx,
        )
        assertEquals(true, r?.succeeded)
        assertTrue(r?.dryRun?.contains("ConfigMap/ns/a") ?: false)
        verify { handle.dryRun().fieldManager(any()).forceConflicts().serverSideApply() }
    }

    @Test
    fun `parse failure surfaces as a parse row in failed`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.load(any()) } throws RuntimeException("yaml broken")
        val r = ApplyManifestRoute(groups, pool).runExecute(
            call(id.toString(), """{"manifest":"---\nnot-yaml: ::: ["}"""), ctx,
        )
        assertEquals(false, r?.succeeded)
        assertEquals("<parse>", r?.failed?.single()?.resource)
        assertTrue(r?.failed?.single()?.error?.contains("yaml broken") ?: false)
    }

    @Test
    fun `blank manifest returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val c = call(id.toString(), """{"manifest":""}""")
        assertNull(ApplyManifestRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller is rejected`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        kotlin.test.assertFailsWith<SecurityException> {
            ApplyManifestRoute(groups, pool).runExecute(
                call(UUID.random().toString(), """{"manifest":"---"}"""), ctx,
            )
        }
    }
}
