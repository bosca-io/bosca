package bosca.analytics.livesessions.nats

import bosca.analytics.model.LiveSession
import bosca.nats.NatsConnectionPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Subject/version encoding for [NatsLiveSessions] — the load-bearing "appVersion contains dots" trap. */
class NatsLiveSessionsTest {

    @Test
    fun `encodes version dots as underscores so a version is a single subject token`() {
        assertEquals("1_2_3", NatsLiveSessions.encodeVersion("1.2.3"))
    }

    @Test
    fun `encodes a null or blank version as the no-version token`() {
        assertEquals(NatsLiveSessions.NO_VERSION, NatsLiveSessions.encodeVersion(null))
        assertEquals(NatsLiveSessions.NO_VERSION, NatsLiveSessions.encodeVersion(""))
    }

    @Test
    fun `builds a publish subject under the prefix`() {
        assertEquals("sessions.geo.app-1._none", NatsLiveSessions.subject("app-1", null))
        assertEquals("sessions.geo.app-1.2_0_0", NatsLiveSessions.subject("app-1", "2.0.0"))
    }

    @Test
    fun `builds a wildcard filter for all versions`() {
        assertEquals("sessions.geo.app-1.*", NatsLiveSessions.subjectFilter("app-1", null))
    }

    @Test
    fun `builds a specific filter for one version`() {
        assertEquals("sessions.geo.app-1.2_0_0", NatsLiveSessions.subjectFilter("app-1", "2.0.0"))
    }

    @Test
    fun `builds a catch-all filter for all applications`() {
        assertEquals("sessions.geo.>", NatsLiveSessions.subjectFilter(null, null))
    }

    @Test
    fun `builds an any-application filter for one version`() {
        assertEquals("sessions.geo.*.2_0_0", NatsLiveSessions.subjectFilter(null, "2.0.0"))
    }

    @Test
    fun `publish initializes existing stream once`() = runTest {
        val connection = mockk<Connection>()
        val management = mockk<JetStreamManagement>()
        val jetStream = mockk<JetStream>()
        every { connection.jetStreamManagement() } returns management
        every { connection.jetStream() } returns jetStream
        every { management.streamNames } returns listOf(NatsLiveSessions.STREAM)
        every { jetStream.publish(any(), any<ByteArray>()) } returns mockk()
        val service = NatsLiveSessions(NatsConnectionPool(connection), Json)

        service.publish("app", LiveSession("session", 1.0, 2.0))
        service.publish("app", LiveSession("session", 1.0, 2.0))

        verify(exactly = 1) { management.streamNames }
        verify(exactly = 2) { jetStream.publish(any(), any<ByteArray>()) }
    }

    @Test
    fun `publish tolerates a concurrent stream creator`() = runTest {
        val connection = mockk<Connection>()
        val management = mockk<JetStreamManagement>()
        val jetStream = mockk<JetStream>()
        every { connection.jetStreamManagement() } returns management
        every { connection.jetStream() } returns jetStream
        every { management.streamNames } returnsMany listOf(emptyList(), listOf(NatsLiveSessions.STREAM))
        every { management.addStream(any<io.nats.client.api.StreamConfiguration>()) } throws IllegalStateException("created elsewhere")
        every { jetStream.publish(any(), any<ByteArray>()) } returns mockk()

        NatsLiveSessions(NatsConnectionPool(connection), Json)
            .publish("app", LiveSession("session", 1.0, 2.0))

        verify { management.addStream(any<io.nats.client.api.StreamConfiguration>()) }
    }

    @Test
    fun `publish preserves stream creation failure when the stream remains absent`() = runTest {
        val connection = mockk<Connection>()
        val management = mockk<JetStreamManagement>()
        every { connection.jetStreamManagement() } returns management
        every { management.streamNames } returns emptyList()
        every { management.addStream(any<io.nats.client.api.StreamConfiguration>()) } throws
            IllegalStateException("creation failed")

        val failure = assertFailsWith<IllegalStateException> {
            NatsLiveSessions(NatsConnectionPool(connection), Json)
                .publish("app", LiveSession("session", 1.0, 2.0))
        }

        assertEquals("creation failed", failure.message)
        verify(exactly = 2) { management.streamNames }
    }

    @Test
    fun `publish retries stream discovery after a transient lookup failure`() = runTest {
        val connection = mockk<Connection>()
        val management = mockk<JetStreamManagement>()
        val jetStream = mockk<JetStream>()
        every { connection.jetStreamManagement() } returns management
        every { connection.jetStream() } returns jetStream
        every { management.streamNames } throws IllegalStateException("lookup") andThen
            listOf(NatsLiveSessions.STREAM)
        every { jetStream.publish(any(), any<ByteArray>()) } returns mockk()
        val service = NatsLiveSessions(NatsConnectionPool(connection), Json)

        assertFailsWith<IllegalStateException> {
            service.publish("app", LiveSession("session", 1.0, 2.0))
        }
        service.publish("app", LiveSession("session", 1.0, 2.0))

        verify(exactly = 2) { management.streamNames }
        verify(exactly = 1) { jetStream.publish(any(), any<ByteArray>()) }
    }
}
