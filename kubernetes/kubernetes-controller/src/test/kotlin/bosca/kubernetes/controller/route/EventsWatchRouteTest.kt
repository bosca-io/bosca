package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.Event
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [EventsWatchRoute] — the live event ticker. ADDED/MODIFIED
 * actions are emitted, DELETED/ERROR are skipped, namespace narrowing
 * routes through `inNamespace`. The same pre-buffer-and-close trick
 * used by [HelmReleaseStatusWatchRouteTest] is applied here.
 */
@OptIn(ExperimentalUuidApi::class)
class EventsWatchRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    private fun event(name: String, ns: String, type: String, reason: String) =
        EventBuilder()
            .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("p").build())
            .withType(type).withReason(reason).withLastTimestamp("2026-05-16T17:00:00Z")
            .build()

    private fun stubAnyNamespaceWatch(events: List<Pair<Watcher.Action, Event>>): Watch {
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<Event>>()
        every {
            client.v1().events().inAnyNamespace().watch(capture(slot))
        } answers {
            val w = slot.captured
            for ((action, ev) in events) w.eventReceived(action, ev)
            w.onClose(null)
            watch
        }
        return watch
    }

    private fun stubNamespacedWatch(namespace: String, events: List<Pair<Watcher.Action, Event>>): Watch {
        val watch = mockk<Watch>(relaxed = true)
        val slot = slot<Watcher<Event>>()
        every {
            client.v1().events().inNamespace(namespace).watch(capture(slot))
        } answers {
            val w = slot.captured
            for ((action, ev) in events) w.eventReceived(action, ev)
            w.onClose(null)
            watch
        }
        return watch
    }

    @Test
    fun `streams ADDED and MODIFIED events as NDJSON`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubAnyNamespaceWatch(listOf(
            Watcher.Action.ADDED to event("e1", "default", "Normal", "Pulled"),
            Watcher.Action.MODIFIED to event("e2", "default", "Warning", "Unhealthy"),
        ))

        val c = call(id.toString())
        val cap = c.captureStream()
        EventsWatchRoute(groups, pool, json).runExecute(c, ctx)

        assertEquals(2, cap.lines.size)
        assertTrue(cap.lines[0].contains("Pulled"))
        assertTrue(cap.lines[1].contains("Unhealthy"))
    }

    @Test
    fun `DELETED and ERROR actions are not emitted`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubAnyNamespaceWatch(listOf(
            Watcher.Action.DELETED to event("e1", "default", "Normal", "Pulled"),
            Watcher.Action.ERROR to event("e2", "default", "Normal", "Pulled"),
        ))

        val c = call(id.toString())
        val cap = c.captureStream()
        EventsWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(0, cap.lines.size)
    }

    @Test
    fun `namespace narrows via inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubNamespacedWatch("prod", listOf(
            Watcher.Action.ADDED to event("e1", "prod", "Normal", "Pulled"),
        ))

        val c = call(id.toString(), mapOf("namespace" to "prod"))
        val cap = c.captureStream()
        EventsWatchRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
    }

    @Test
    fun `watch is closed when the stream exits`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = stubAnyNamespaceWatch(emptyList())

        val c = call(id.toString())
        c.captureStream()
        EventsWatchRoute(groups, pool, json).runExecute(c, ctx)
        verify { watch.close() }
    }

    @Test
    fun `watch is closed when cancellation lands during acquisition`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val watch = mockk<Watch>(relaxed = true)
        lateinit var routeJob: Job
        every { client.v1().events().inAnyNamespace().watch(any()) } answers {
            routeJob.cancel(CancellationException("client disconnected"))
            watch
        }

        val c = call(id.toString())
        c.captureStream()
        routeJob = launch(start = CoroutineStart.LAZY) {
            EventsWatchRoute(groups, pool, json).runExecute(c, ctx)
        }

        routeJob.start()
        routeJob.join()

        verify { watch.close() }
    }

    @Test
    fun `non-admin is rejected before the stream opens`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            EventsWatchRoute(groups, pool, json).runExecute(call(UUID.random().toString()), ctx)
        }
    }
}
