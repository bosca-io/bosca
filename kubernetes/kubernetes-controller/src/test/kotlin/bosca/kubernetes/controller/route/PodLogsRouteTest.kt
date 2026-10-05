package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.EventLevel
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.LogWatch
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [PodLogsRoute]. Differs from the watcher-based streaming
 * routes — this one reads from a fabric8 [LogWatch] (follow path) or
 * a raw `logInputStream` (non-follow path). We prepare an in-memory
 * input stream with a few kubelet-style log lines and let the route
 * drain it.
 *
 * Each line's `parseLine` strips the RFC3339Nano timestamp prefix and
 * classifies severity via [bosca.kubernetes.model.LogLevelInference].
 */
@OptIn(ExperimentalUuidApi::class)
class PodLogsRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(path: Map<String, String>, query: Map<String, String> = emptyMap()): ServerCall {
        val c = mockk<ServerCall>(relaxed = true)
        every { c.pathParameters } returns Parameters.fromSingleValueMap(path)
        every { c.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return c
    }

    private val sampleLog = """
        2026-05-16T17:00:00.000000000Z starting up
        2026-05-16T17:00:01.000000000Z handling request
        2026-05-16T17:00:02.000000000Z ERROR: connection refused
    """.trimIndent() + "\n"

    private fun stubFollowChain(container: String?, body: String): LogWatch {
        val logWatch = mockk<LogWatch>(relaxed = true)
        every { logWatch.output } returns ByteArrayInputStream(body.toByteArray())

        if (container != null) {
            every {
                client.pods().inNamespace("prod").withName("api")
                    .inContainer(container).usingTimestamps().tailingLines(any()).watchLog()
            } returns logWatch
        } else {
            every {
                client.pods().inNamespace("prod").withName("api")
                    .usingTimestamps().tailingLines(any()).watchLog()
            } returns logWatch
        }
        return logWatch
    }

    private fun stubNonFollowChain(body: String) {
        every {
            client.pods().inNamespace("prod").withName("api")
                .usingTimestamps().tailingLines(any()).logInputStream
        } returns ByteArrayInputStream(body.toByteArray())
    }

    @Test
    fun `streams log lines as NDJSON LogLine records`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubFollowChain(null, sampleLog)

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        val cap = c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)

        assertEquals(3, cap.lines.size)
        assertTrue(cap.lines[0].contains("\"message\":\"starting up\""))
        assertTrue(cap.lines[0].contains("\"timestamp\":\"2026-05-16T17:00:00.000000000Z\""))
        assertTrue(cap.lines[2].contains("\"level\":\"${EventLevel.ERROR}\""))
    }

    @Test
    fun `container query routes through inContainer`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubFollowChain("sidecar", sampleLog)

        val c = call(
            mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"),
            mapOf("container" to "sidecar"),
        )
        c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)
        verify {
            client.pods().inNamespace("prod").withName("api")
                .inContainer("sidecar").usingTimestamps().tailingLines(any()).watchLog()
        }
    }

    @Test
    fun `follow=false reads through logInputStream and closes`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubNonFollowChain(sampleLog)

        val c = call(
            mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"),
            mapOf("follow" to "false"),
        )
        val cap = c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(3, cap.lines.size)
    }

    @Test
    fun `tailLines is forwarded to fabric8`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val tailSlot = slot<Int>()
        val logWatch = mockk<LogWatch>(relaxed = true)
        every { logWatch.output } returns ByteArrayInputStream("".toByteArray())
        every {
            client.pods().inNamespace("prod").withName("api")
                .usingTimestamps().tailingLines(capture(tailSlot)).watchLog()
        } returns logWatch

        val c = call(
            mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"),
            mapOf("tailLines" to "42"),
        )
        c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(42, tailSlot.captured)
    }

    @Test
    fun `negative tailLines coerces to zero`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val tailSlot = slot<Int>()
        val logWatch = mockk<LogWatch>(relaxed = true)
        every { logWatch.output } returns ByteArrayInputStream("".toByteArray())
        every {
            client.pods().inNamespace("prod").withName("api")
                .usingTimestamps().tailingLines(capture(tailSlot)).watchLog()
        } returns logWatch

        val c = call(
            mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"),
            mapOf("tailLines" to "-5"),
        )
        c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(0, tailSlot.captured)
    }

    @Test
    fun `lines without a timestamp prefix are emitted with empty timestamp`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        stubFollowChain(null, "no timestamp here\n")

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        val cap = c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)
        assertEquals(1, cap.lines.size)
        assertTrue(cap.lines.single().contains("\"timestamp\":\"\""))
        assertTrue(cap.lines.single().contains("no timestamp here"))
    }

    @Test
    fun `LogWatch is closed when the follow stream ends`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val lw = stubFollowChain(null, sampleLog)

        val c = call(mapOf("id" to id.toString(), "namespace" to "prod", "name" to "api"))
        c.captureStream()
        PodLogsRoute(groups, pool, json).runExecute(c, ctx)
        verify { lw.close() }
    }

    @Test
    fun `missing namespace returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "name" to "api"))
        assertNull(PodLogsRoute(groups, pool, json).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `missing name returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val c = call(mapOf("id" to UUID.random().toString(), "namespace" to "prod"))
        assertNull(PodLogsRoute(groups, pool, json).runExecute(c, ctx))
        verify { c.respond(HttpStatusCode.BadRequest) }
    }

    @Test
    fun `non-admin caller is rejected`() = runTest {
        every { groups.verifyHasAdminGroup(any()) } throws SecurityException("nope")
        assertFailsWith<SecurityException> {
            PodLogsRoute(groups, pool, json).runExecute(
                call(mapOf("id" to UUID.random().toString(), "namespace" to "n", "name" to "x")), ctx,
            )
        }
    }
}
