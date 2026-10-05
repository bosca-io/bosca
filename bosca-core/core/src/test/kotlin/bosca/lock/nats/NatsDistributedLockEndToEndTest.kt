package bosca.lock.nats

import bosca.nats.NatsConnectionPool
import bosca.test.resources.SharedNatsContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NatsDistributedLockEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var factory: NatsDistributedLockFactory

    @BeforeTest
    fun setup() = runTest {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*\\s", 1))
        natsContainer.start()

        natsPool = natsContainer.newConnectionPool(1)
        factory = NatsDistributedLockFactory(natsPool)
    }

    @AfterTest
    fun teardown() = runTest {
        if (this@NatsDistributedLockEndToEndTest::natsPool.isInitialized) natsPool.close()
        natsContainer.stop()
    }

    @Test
    fun `tryAcquire and release`() = runTest {
        val lock = factory.create("test-lock")
        assertFalse(lock.isHeld)

        assertTrue(lock.tryAcquire(30_000))
        assertTrue(lock.isHeld)

        assertTrue(lock.release())
        assertFalse(lock.isHeld)
    }

    @Test
    fun `second lock cannot acquire while first is held`() = runTest {
        val lock1 = factory.create("contended-lock")
        val lock2 = factory.create("contended-lock")

        assertTrue(lock1.tryAcquire(30_000))
        assertFalse(lock2.tryAcquire(30_000))

        assertTrue(lock1.release())
        assertTrue(lock2.tryAcquire(30_000))
        assertTrue(lock2.release())
    }

    @Test
    fun `acquire waits and times out`() = runTest {
        val lock1 = factory.create("timeout-lock")
        val lock2 = factory.create("timeout-lock")

        assertTrue(lock1.tryAcquire(30_000))
        assertFalse(lock2.acquire(30_000, waitTimeoutMillis = 300, retryDelayMillis = 50))

        assertTrue(lock1.release())
    }

    @Test
    fun `renew extends held lock`() = runTest {
        val lock = factory.create("renew-lock")
        assertTrue(lock.tryAcquire(30_000))
        assertTrue(lock.renew(30_000))
        assertTrue(lock.isHeld)
        assertTrue(lock.release())
    }

    @Test
    fun `overlapping renewals by the lock's own holder all succeed`() = runTest {
        val lock = factory.create("overlapping-renew-lock")
        assertTrue(lock.tryAcquire(30_000))

        // Each renewal moves the key's revision; one racing another must not read as a lost lock.
        val results = (1..20).map { async(Dispatchers.IO) { lock.renew(30_000) } }.awaitAll()

        assertTrue(results.all { it }, "Every renewal by the holder must succeed: $results")
        assertTrue(lock.release())
    }

    @Test
    fun `withLock returns null when the lock is held elsewhere`() = runTest {
        val holder = factory.create("with-lock-held")
        assertTrue(holder.tryAcquire(30_000))

        val result = factory.create("with-lock-held").withLock(30_000, waitTimeoutMillis = 200, retryDelayMillis = 50) { "ran" }

        assertEquals(null, result, "Not acquired: null, per the DistributedLock contract")
        assertTrue(holder.release())
    }

    @Test
    fun `withLock releases even when its holder is cancelled`() = runTest {
        val lock = factory.create("with-lock-cancelled")
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lock.withLock(30_000) {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        started.await()
        holder.cancelAndJoin()

        assertTrue(factory.create("with-lock-cancelled").tryAcquire(30_000), "The cancelled holder must have released")
    }

    @Test
    fun `withLock executes block and releases`() = runTest {
        val lock = factory.create("with-lock")
        val result = lock.withLock(30_000) { "done" }
        assertEquals("done", result)
        assertFalse(lock.isHeld)
    }

    @Test
    fun `concurrent withLock serializes access`() = runTest {
        var counter = 0
        val iterations = 10

        val jobs = (1..iterations).map {
            async {
                val lock = factory.create("serial-lock")
                lock.withLock(5_000, waitTimeoutMillis = 10_000, retryDelayMillis = 50) {
                    val current = counter
                    counter = current + 1
                }
            }
        }
        jobs.awaitAll()

        assertEquals(iterations, counter)
    }
}
