package bosca.nats

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.Options
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NatsPoolableConnectionFactoryTest {

    private val factory = NatsPoolableConnectionFactory(
        Options.Builder().server("nats://127.0.0.1:4222").build(),
        maxConnections = 3,
    )

    @Test
    fun `validation and liveness cover connected disconnected and failing clients`() = runTest {
        val connected = mockk<Connection> { every { status } returns Connection.Status.CONNECTED }
        val disconnected = mockk<Connection> { every { status } returns Connection.Status.DISCONNECTED }
        val failing = mockk<Connection> { every { status } throws IllegalStateException("closed") }

        assertTrue(factory.validate(connected))
        assertFalse(factory.validate(disconnected))
        assertFalse(factory.validate(failing))
        assertTrue(factory.isAlive(connected))
        assertFalse(factory.isAlive(disconnected))
    }

    @Test
    fun `destroy closes clients and contains close failures`() = runTest {
        val healthy = mockk<Connection> { every { close() } just Runs }
        val failing = mockk<Connection> { every { close() } throws IllegalStateException("close") }

        factory.destroy(healthy)
        factory.destroy(failing)

        verify(exactly = 1) { healthy.close() }
        verify(exactly = 1) { failing.close() }
    }
}
