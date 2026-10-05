package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.controller.util.HelmReleaseDecoder
import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.K8sHelmRelease
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [HelmReleaseStatusWatchRoute]. The trick for testing streaming
 * routes: when fabric8's `.watch(watcher)` is mocked, the answer block
 * captures the watcher, fires the events the test cares about, then
 * calls `onClose(null)`. The channel inside the route buffers the
 * events (capacity 32) and the consumer loop drains them and exits
 * cleanly via the close signal — no test-side concurrency needed.
 */
@OptIn(ExperimentalUuidApi::class)
class HelmReleaseStatusWatchRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<io.fabric8.kubernetes.client.KubernetesClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        return c
    }

    private fun secret(name: String, namespace: String, version: Int) = SecretBuilder()
        .withNewMetadata()
            .withName("sh.helm.release.v1.$name.v$version")
            .withNamespace(namespace)
            .addToLabels("owner", "helm")
            .addToLabels("name", name)
            .addToLabels("version", version.toString())
        .endMetadata()
        .withType("helm.sh/release.v1")
        .addToData("release", "stub")
        .build()

    private fun release(version: Int) = K8sHelmRelease(
        id = "prod/api/$version", name = "api", namespace = "prod",
        chart = "nginx", chartVersion = "1.0.$version", appVersion = "1.27.0",
        revision = version, status = HelmStatus.DEPLOYED, updated = "now", installed = "1d",
        repo = "", repoUrl = "", description = "",
    )

    private fun stubWatch(events: List<Pair<Watcher.Action, Secret>>): Watch {
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<Secret>>()
        every {
            client.secrets().inNamespace("prod")
                .withLabel("owner", "helm").withLabel("name", "api")
                .watch(capture(slot))
        } answers {
            val w = slot.captured
            for ((action, sec) in events) w.eventReceived(action, sec)
            w.onClose(null)
            watch
        }
        return watch
    }

    @Test
    fun `streams the highest-revision release on each watch update`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val s1 = secret("api", "prod", 1)
        val s2 = secret("api", "prod", 2)
        every { HelmReleaseDecoder.isHelmReleaseSecret(any()) } returns true
        every { HelmReleaseDecoder.decodeRelease(s1, any(), any()) } returns release(1)
        every { HelmReleaseDecoder.decodeRelease(s2, any(), any()) } returns release(2)
        stubWatch(listOf(
            Watcher.Action.ADDED to s1,
            Watcher.Action.MODIFIED to s2,
        ))

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        val cap = c.captureStream()

        HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)

        assertEquals(2, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"revision\":1"))
        assertTrue(cap.lines[1].contains("\"revision\":2"))
    }

    @Test
    fun `older-revision events are dropped by the high-water-mark`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val s2 = secret("api", "prod", 2)
        val s1 = secret("api", "prod", 1)
        every { HelmReleaseDecoder.isHelmReleaseSecret(any()) } returns true
        every { HelmReleaseDecoder.decodeRelease(s2, any(), any()) } returns release(2)
        every { HelmReleaseDecoder.decodeRelease(s1, any(), any()) } returns release(1)
        stubWatch(listOf(
            Watcher.Action.ADDED to s2,
            Watcher.Action.MODIFIED to s1,
        ))

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        val cap = c.captureStream()

        HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        // s1 (revision 1) was below the existing high-water mark of 2.
        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines.single().contains("\"revision\":2"))
    }

    @Test
    fun `non-helm secrets are skipped`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val s = secret("api", "prod", 1)
        every { HelmReleaseDecoder.isHelmReleaseSecret(s) } returns false
        stubWatch(listOf(Watcher.Action.ADDED to s))

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        val cap = c.captureStream()
        HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(0, cap.lines.size)
    }

    @Test
    fun `DELETED and ERROR actions are not emitted`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client

        val s = secret("api", "prod", 1)
        every { HelmReleaseDecoder.isHelmReleaseSecret(s) } returns true
        every { HelmReleaseDecoder.decodeRelease(s, any(), any()) } returns release(1)
        stubWatch(listOf(
            Watcher.Action.DELETED to s,
            Watcher.Action.ERROR to s,
        ))

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        val cap = c.captureStream()
        HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(0, cap.lines.size)
    }

    @Test
    fun `missing namespace returns 400 without opening a stream`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "name" to "api"))
        val cap = c.captureStream()
        assertNull(HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
        assertEquals(0, cap.lines.size)
    }

    @Test
    fun `missing name returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "namespace" to "prod"))
        assertNull(HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller is rejected before stream opens`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        val c = call(mapOf("id" to UUID.random().toString(), "namespace" to "n", "name" to "x"))
        assertFailsWith<SecurityException> {
            HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        }
    }

    @Test
    fun `watch is closed when the stream exits`() = runTest {
        mockkObject(HelmReleaseDecoder)
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = stubWatch(emptyList())

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        c.captureStream()
        HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)

        verify { watch.close() }
    }

    @Test
    fun `watch is closed when cancellation lands during acquisition`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        lateinit var routeJob: Job
        every {
            client.secrets().inNamespace("prod")
                .withLabel("owner", "helm").withLabel("name", "api")
                .watch(any())
        } answers {
            routeJob.cancel(CancellationException("client disconnected"))
            watch
        }

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        c.captureStream()
        routeJob = launch(start = CoroutineStart.LAZY) {
            HelmReleaseStatusWatchRoute(groups, pool, json).runExecute(c, ctx)
        }

        routeJob.start()
        routeJob.join()

        verify { watch.close() }
    }
}
