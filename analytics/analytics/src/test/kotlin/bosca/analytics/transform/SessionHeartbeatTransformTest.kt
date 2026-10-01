package bosca.analytics.transform

import bosca.analytics.livesessions.LiveSessionsService
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.Geo
import bosca.analytics.model.LiveSession
import bosca.counter.Counter
import bosca.di.ObjectProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SessionHeartbeatTransform]: the single heartbeat stage — publishes a geo-tagged live session to the live
 * map, counts the heartbeat into the active-session counter, then strips it from the batch.
 */
class SessionHeartbeatTransformTest {

    private val now = 900_000L
    private val counter = mockk<Counter>(relaxed = true)
    private val liveSessions = mockk<LiveSessionsService>(relaxed = true)
    private val provider = mockk<ObjectProvider<LiveSessionsService>> {
        coEvery { get() } returns liveSessions
    }
    private val transform = SessionHeartbeatTransform(counter, provider) { now }
    private val pipelineContext = mockk<EventPipelineContext>(relaxed = true)

    private fun ctx(location: Geo? = Geo(latitude = 40.7, longitude = -74.0)) = mockk<EventContext> {
        every { appId } returns "mobile"
        every { appVersion } returns "1.0.0"
        every { sessionId } returns "s1"
        every { geo } returns location
    }

    // received = 900_000 ms → 900 s → 15-min bucket index 1 → key "sessions.mobile.1".
    private fun batch(
        vararg types: EventType,
        context: EventContext? = ctx(),
        received: Long? = now,
    ) = Events(
        context = context,
        events = types.map { Event(created = 900_000L, type = it) },
        sent = 900_000L,
        sentMicros = 0,
        received = received,
    )

    // --- active-session counting + strip ---

    @Test
    fun `counts a heartbeat into the app minute-bucket and drops it`() = runTest {
        val result = transform.transform(pipelineContext, batch(EventType.Heartbeat))

        coVerify(exactly = 1) { counter.increment("sessions.mobile.1") }
        assertTrue(result.events.isEmpty(), "the heartbeat must not be stored")
    }

    @Test
    fun `counts every heartbeat but keeps the other events`() = runTest {
        val result = transform.transform(
            pipelineContext,
            batch(EventType.Heartbeat, EventType.Interaction, EventType.Heartbeat),
        )

        coVerify(exactly = 2) { counter.increment("sessions.mobile.1") }
        assertEquals(listOf(EventType.Interaction), result.events.map { it.type })
    }

    @Test
    fun `a batch with no heartbeats is passed through untouched`() = runTest {
        val input = batch(EventType.Interaction, EventType.Error)

        val result = transform.transform(pipelineContext, input)

        assertEquals(input, result)
        coVerify(exactly = 0) { counter.increment(any()) }
        coVerify(exactly = 0) { liveSessions.publish(any(), any()) }
    }

    @Test
    fun `falls back to a default app when the batch has no context`() = runTest {
        transform.transform(pipelineContext, batch(EventType.Heartbeat, context = null))

        coVerify(exactly = 1) { counter.increment("sessions.unknown.1") }
    }

    // --- live-map publishing ---

    @Test
    fun `publishes a geo-tagged heartbeat as a live session`() = runTest {
        transform.transform(pipelineContext, batch(EventType.Heartbeat))

        coVerify(exactly = 1) {
            liveSessions.publish("mobile", LiveSession(sessionId = "s1", latitude = 40.7, longitude = -74.0, appVersion = "1.0.0"))
        }
    }

    @Test
    fun `publishes once per batch even with multiple heartbeats`() = runTest {
        transform.transform(pipelineContext, batch(EventType.Heartbeat, EventType.Heartbeat))

        coVerify(exactly = 1) { liveSessions.publish(any(), any()) }
    }

    @Test
    fun `does not republish an expired heartbeat but still counts and strips it`() = runTest {
        val result = transform.transform(
            pipelineContext,
            batch(EventType.Heartbeat, received = now - SessionHeartbeatTransform.BUCKET_MILLIS),
        )

        coVerify(exactly = 0) { liveSessions.publish(any(), any()) }
        coVerify(exactly = 1) { counter.increment("sessions.mobile.0") }
        assertTrue(result.events.isEmpty(), "the expired heartbeat must still be consumed")
    }

    @Test
    fun `still counts a heartbeat that has no geo but does not put it on the map`() = runTest {
        transform.transform(pipelineContext, batch(EventType.Heartbeat, context = ctx(location = null)))

        coVerify(exactly = 0) { liveSessions.publish(any(), any()) }
        coVerify(exactly = 1) { counter.increment("sessions.mobile.1") }
    }

    @Test
    fun `does not publish when a coordinate is missing`() = runTest {
        transform.transform(
            pipelineContext,
            batch(EventType.Heartbeat, context = ctx(location = Geo(latitude = null, longitude = -74.0))),
        )

        coVerify(exactly = 0) { liveSessions.publish(any(), any()) }

        transform.transform(
            pipelineContext,
            batch(EventType.Heartbeat, context = ctx(location = Geo(latitude = 40.7, longitude = null))),
        )
        coVerify(exactly = 0) { liveSessions.publish(any(), any()) }
    }

    @Test
    fun `does not publish when the batch has no context`() = runTest {
        transform.transform(pipelineContext, batch(EventType.Heartbeat, context = null))

        coVerify(exactly = 0) { liveSessions.publish(any(), any()) }
    }
}
