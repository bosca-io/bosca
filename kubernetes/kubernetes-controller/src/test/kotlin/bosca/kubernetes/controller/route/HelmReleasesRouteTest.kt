package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.HelmReleaseDecoder
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRevision
import bosca.kubernetes.model.HelmStatus
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.SecretListBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [HelmReleasesRoute] and [HelmReleaseHistoryRoute]. The release
 * Secret format (doubly base64+gzipped JSON) is exercised by the
 * [HelmReleaseDecoder] unit test; this one mocks the decoder so the
 * route's job — `owner=helm` Secret listing, dedupe-by-highest-revision,
 * label-filtered namespace lookup — is what gets asserted.
 */
@OptIn(ExperimentalUuidApi::class)
class HelmReleasesRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    private fun helmSecret(name: String, namespace: String, version: Int) = SecretBuilder()
        .withNewMetadata()
            .withName("sh.helm.release.v1.$name.v$version")
            .withNamespace(namespace)
            .withUid("uid-$name-$version")
            .addToLabels("owner", "helm")
            .addToLabels("name", name)
            .addToLabels("version", version.toString())
        .endMetadata()
        .withType("helm.sh/release.v1")
        .addToData("release", "stub")
        .build()

    private fun release(name: String, namespace: String, revision: Int) = K8sHelmRelease(
        id = "$namespace/$name/$revision",
        name = name,
        namespace = namespace,
        chart = "nginx",
        chartVersion = "1.0.$revision",
        appVersion = "1.27.0",
        revision = revision,
        status = HelmStatus.DEPLOYED,
        updated = "now",
        installed = "1d",
        repo = "",
        repoUrl = "",
        description = "",
    )

    @Test
    fun `releases route dedupes to the highest revision per release`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val s1 = helmSecret("api", "prod", 1)
        val s2 = helmSecret("api", "prod", 2)
        val s3 = helmSecret("worker", "prod", 1)

        every { client.secrets().inAnyNamespace().withLabel("owner", "helm").list() } returns
            SecretListBuilder().withItems(listOf(s1, s2, s3)).build()
        every { HelmReleaseDecoder.isHelmReleaseSecret(any()) } returns true
        every { HelmReleaseDecoder.decodeRelease(s1, any(), any()) } returns release("api", "prod", 1)
        every { HelmReleaseDecoder.decodeRelease(s2, any(), any()) } returns release("api", "prod", 2)
        every { HelmReleaseDecoder.decodeRelease(s3, any(), any()) } returns release("worker", "prod", 1)

        val r = HelmReleasesRoute(groups, pool).runExecute(call(mapOf("id" to id.toString())), ctx)
        val byName = r?.items?.associateBy { it.name }
        assertEquals(2, byName?.get("api")?.revision)   // highest revision kept
        assertEquals(1, byName?.get("worker")?.revision)
    }

    @Test
    fun `releases route skips non-helm secrets`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val helm = helmSecret("api", "prod", 1)
        val other = SecretBuilder()
            .withNewMetadata().withName("other").withNamespace("prod").endMetadata().build()
        every { client.secrets().inAnyNamespace().withLabel("owner", "helm").list() } returns
            SecretListBuilder().withItems(listOf(helm, other)).build()

        every { HelmReleaseDecoder.isHelmReleaseSecret(helm) } returns true
        every { HelmReleaseDecoder.isHelmReleaseSecret(other) } returns false
        every { HelmReleaseDecoder.decodeRelease(helm, any(), any()) } returns release("api", "prod", 1)

        val r = HelmReleasesRoute(groups, pool).runExecute(call(mapOf("id" to id.toString())), ctx)
        assertEquals(listOf("api"), r?.items?.map { it.name })
    }

    @Test
    fun `releases route skips secrets the decoder rejects`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val bad = helmSecret("api", "prod", 1)
        every { client.secrets().inAnyNamespace().withLabel("owner", "helm").list() } returns
            SecretListBuilder().withItems(listOf(bad)).build()
        every { HelmReleaseDecoder.isHelmReleaseSecret(bad) } returns true
        every { HelmReleaseDecoder.decodeRelease(bad, any(), any()) } returns null

        val r = HelmReleasesRoute(groups, pool).runExecute(call(mapOf("id" to id.toString())), ctx)
        assertEquals(0, r?.items?.size)
    }

    @Test
    fun `releases route narrows via inNamespace when namespace query is set`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val helm = helmSecret("api", "prod", 1)
        every { client.secrets().inNamespace("prod").withLabel("owner", "helm").list() } returns
            SecretListBuilder().withItems(listOf(helm)).build()
        every { HelmReleaseDecoder.isHelmReleaseSecret(helm) } returns true
        every { HelmReleaseDecoder.decodeRelease(helm, any(), any()) } returns release("api", "prod", 1)

        HelmReleasesRoute(groups, pool).runExecute(
            call(mapOf("id" to id.toString()), mapOf("namespace" to "prod")), ctx,
        )
        verify { client.secrets().inNamespace("prod").withLabel("owner", "helm").list() }
    }

    @Test
    fun `history route returns revisions sorted newest-first`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val s1 = helmSecret("api", "prod", 1)
        val s2 = helmSecret("api", "prod", 3)
        val s3 = helmSecret("api", "prod", 2)
        every {
            client.secrets().inNamespace("prod").withLabel("owner", "helm").withLabel("name", "api").list()
        } returns SecretListBuilder().withItems(listOf(s1, s2, s3)).build()

        every { HelmReleaseDecoder.decodeRevision(s1, any()) } returns rev(1)
        every { HelmReleaseDecoder.decodeRevision(s2, any()) } returns rev(3)
        every { HelmReleaseDecoder.decodeRevision(s3, any()) } returns rev(2)

        val r = HelmReleaseHistoryRoute(groups, pool).runExecute(
            call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api")), ctx,
        )
        assertEquals(listOf(3, 2, 1), r?.items?.map { it.revision })
    }

    @Test
    fun `history route returns 400 when path parameters are missing`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "namespace" to "prod"))
        assertNull(HelmReleaseHistoryRoute(groups, pool).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller is rejected on both routes`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            HelmReleasesRoute(groups, pool).runExecute(call(mapOf("id" to UUID.random().toString())), ctx)
        }
        assertFailsWith<SecurityException> {
            HelmReleaseHistoryRoute(groups, pool).runExecute(
                call(mapOf("id" to UUID.random().toString(), "namespace" to "n", "name" to "x")), ctx,
            )
        }
    }

    private fun rev(version: Int) = K8sHelmRevision(
        revision = version,
        updated = "now",
        status = HelmStatus.DEPLOYED,
        chart = "nginx",
        appVersion = "1.27.0",
        description = "",
    )
}
