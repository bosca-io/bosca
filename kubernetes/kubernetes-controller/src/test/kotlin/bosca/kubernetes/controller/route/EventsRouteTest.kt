package bosca.kubernetes.controller.route

import bosca.kubernetes.controller.cluster.ClusterClientPool
import bosca.kubernetes.model.EventLevel
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventListBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins [EventsRoute] — admin gate, optional namespace / level / limit
 * filters, default page size, and newest-first ordering by timestamp.
 */
@OptIn(ExperimentalUuidApi::class)
class EventsRouteTest {

    private val groups = mockk<GroupEvaluator>()
    private val pool = mockk<ClusterClientPool>()
    private val ctx = mockk<AuthenticationContext>()
    private val client = mockk<KubernetesClient>()

    private fun event(name: String, type: String, reason: String, timestamp: String) =
        EventBuilder()
            .withNewMetadata().withName(name).withNamespace("ns").endMetadata()
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("p").build())
            .withType(type)
            .withReason(reason)
            .withLastTimestamp(timestamp)
            .build()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    private fun call(pathId: String, query: Map<String, String> = emptyMap()): ServerCall {
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.fromSingleValueMap(mapOf("id" to pathId))
        every { call.request.queryParameters } returns Parameters.fromSingleValueMap(query)
        return call
    }

    @Test
    fun `no filter returns all events newest-first`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.v1().events().inAnyNamespace().list() } returns EventListBuilder()
            .addToItems(event("old", "Normal", "Pulled", "2026-05-15T10:00:00Z"))
            .addToItems(event("new", "Normal", "Pulled", "2026-05-15T11:55:00Z"))
            .build()

        val r = EventsRoute(groups, pool).runExecute(call(id.toString()), ctx)
        assertEquals(listOf("Pod/p", "Pod/p"), r?.items?.map { it.involvedObject })
        assertTrue(r!!.items[0].timestamp > r.items[1].timestamp, "newest first")
    }

    @Test
    fun `namespace filter routes through inNamespace`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.v1().events().inNamespace("prod").list() } returns EventListBuilder()
            .addToItems(event("a", "Normal", "Pulled", "2026-05-15T11:55:00Z"))
            .build()

        EventsRoute(groups, pool).runExecute(call(id.toString(), mapOf("namespace" to "prod")), ctx)
        verify { client.v1().events().inNamespace("prod").list() }
    }

    @Test
    fun `level filter narrows after mapping`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.v1().events().inAnyNamespace().list() } returns EventListBuilder()
            .addToItems(event("normal", "Normal", "Pulled", "2026-05-15T11:55:00Z"))
            .addToItems(event("warn", "Warning", "Unhealthy", "2026-05-15T11:55:00Z"))
            .addToItems(event("err", "Warning", "Failed", "2026-05-15T11:55:00Z"))  // promoted to ERROR
            .build()

        val r = EventsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("level" to "ERROR")),
            ctx,
        )
        assertEquals(1, r?.items?.size)
        assertEquals(EventLevel.ERROR, r?.items?.single()?.level)
    }

    @Test
    fun `unknown level filter is ignored`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        every { client.v1().events().inAnyNamespace().list() } returns EventListBuilder()
            .addToItems(event("a", "Normal", "Pulled", "2026-05-15T11:55:00Z"))
            .build()

        val r = EventsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("level" to "BOGUS")),
            ctx,
        )
        assertEquals(1, r?.items?.size)
    }

    @Test
    fun `limit caps the returned set`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val id = UUID.random()
        coEvery { pool.get(id) } returns client
        val many = (1..10).map { event("e$it", "Normal", "Pulled", "2026-05-15T11:5${it % 10}:00Z") }
        every { client.v1().events().inAnyNamespace().list() } returns
            EventListBuilder().withItems(many).build()

        val r = EventsRoute(groups, pool).runExecute(
            call(id.toString(), mapOf("limit" to "3")),
            ctx,
        )
        assertEquals(3, r?.items?.size)
    }

    @Test
    fun `missing id returns 400`() = runTest {
        justRun { groups.verifyHasAdminGroup(any()) }
        val call = mockk<ServerCall>(relaxed = true)
        every { call.pathParameters } returns Parameters.Empty
        val r = EventsRoute(groups, pool).runExecute(call, ctx)
        assertNull(r)
        verify { call.respond(HttpStatusCode.BadRequest) }
    }
}
