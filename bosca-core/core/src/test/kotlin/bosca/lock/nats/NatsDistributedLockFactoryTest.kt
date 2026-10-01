package bosca.lock.nats

import bosca.nats.NatsConnectionPool
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.KeyValue
import io.nats.client.KeyValueManagement
import io.nats.client.api.KeyValueEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NatsDistributedLockFactoryTest {

    @Test
    fun factoryCreatesLock() = runTest {
        val connection = mockk<Connection>()
        val nats = NatsConnectionPool(connection)
        val kv = mockk<KeyValue>()

        every { connection.keyValue("locks") } returns kv

        val factory = NatsDistributedLockFactory(nats)
        val lock = factory.create("foo")

        assertNotNull(lock)
        assertTrue(lock is NatsDistributedLock)

        factory.create("bar")
        verify(exactly = 1) { connection.keyValue("locks") }
    }

    @Test
    fun `force release covers present absent and failed entries`() = runTest {
        val connection = mockk<Connection>()
        val nats = NatsConnectionPool(connection)
        val kv = mockk<KeyValue>()
        val entry = mockk<KeyValueEntry>()
        every { connection.keyValue("locks") } returns kv
        every { kv.get("foo-bar") } returns entry
        every { kv.delete("foo-bar") } just Runs
        every { kv.purge("foo-bar") } just Runs
        val factory = NatsDistributedLockFactory(nats)

        assertTrue(factory.forceRelease("foo:bar"))
        verify { kv.delete("foo-bar") }
        verify { kv.purge("foo-bar") }

        every { kv.get("missing") } returns null
        assertFalse(factory.forceRelease("missing"))

        every { kv.get("broken") } throws IllegalStateException("nats unavailable")
        assertFalse(factory.forceRelease("broken"))
    }

    @Test
    fun `factory creates a missing lock bucket and tolerates a concurrent creator`() = runTest {
        suspend fun createWithManagementFailure(fail: Boolean) {
            val connection = mockk<Connection>()
            val management = mockk<KeyValueManagement>(relaxed = true)
            val kv = mockk<KeyValue>()
            every { connection.keyValue("locks") } throws IllegalStateException("missing") andThen kv
            every { connection.keyValueManagement() } returns management
            if (fail) every { management.create(any()) } throws IllegalStateException("already created")

            assertTrue(NatsDistributedLockFactory(NatsConnectionPool(connection)).create("created") is NatsDistributedLock)
            verify { management.create(any()) }
            verify(exactly = 2) { connection.keyValue("locks") }
        }

        createWithManagementFailure(false)
        createWithManagementFailure(true)
    }
}
