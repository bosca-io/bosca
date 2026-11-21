package bosca.kubernetes.service

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServingModelCoordinationTest {
    private val factory = mockk<DistributedLockFactory>()
    private val mutex = Mutex()
    private val handles = mutableListOf<DistributedLock>()
    private var renews = true

    init {
        coEvery { factory.create("kubernetes-serving-model-lifecycle") } answers {
            val owner = Any()
            mockk<DistributedLock>().also { lock ->
                handles += lock
                coEvery { lock.tryAcquire(any()) } answers { mutex.tryLock(owner) }
                coEvery { lock.renew(any()) } answers { renews && mutex.holdsLock(owner) }
                coEvery { lock.release() } answers { mutex.unlock(owner); true }
            }
        }
    }

    @Test
    fun `overlapping lifecycle operations skip while the owner renews and cancellation releases ownership`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val first = launch { withServingModelLock(factory) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        assertNull(withServingModelLock(factory) { error("Must not overlap") })
        advanceTimeBy(20_001)
        runCurrent()
        coVerify { handles.first().renew(60_000) }
        first.cancelAndJoin()
        assertEquals("next", withServingModelLock(factory) { "next" })
    }

    @Test
    fun `lost renewal fails the operation and releases the lock for the next worker`() = runTest {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val operation = async { withServingModelLock(factory) { entered.complete(Unit); awaitCancellation() } }
            entered.await()
            renews = false
            advanceTimeBy(20_001)
            assertFailsWith<IllegalStateException> { operation.await() }
            assertEquals(true, withServingModelLock(factory) { true })
        }
    }

    @Test
    fun `lost lease stops a blocked read before it can drive another mutation`() = runTest {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val gate = CountDownLatch(1)
            var mutated = false
            val operation = async {
                withServingModelLock(factory) { lease ->
                    lease.blocking {
                        entered.complete(Unit)
                        // Some blocking APIs finish their request even after interruption.
                        while (true) {
                            try {
                                check(gate.await(10, TimeUnit.SECONDS))
                                break
                            } catch (_: InterruptedException) { /* Complete the in-flight read. */ }
                        }
                    }
                    lease.blocking { mutated = true }
                }
            }
            entered.await()
            renews = false
            advanceTimeBy(20_001)
            runCurrent()
            gate.countDown()
            assertFailsWith<IllegalStateException> { operation.await() }
            assertEquals(false, mutated)
        }
    }

    @Test
    fun `ownership is checked before a blocking mutation even before the next scheduled renewal`() = runTest {
        var mutated = false
        assertFailsWith<IllegalStateException> {
            withServingModelLock(factory) { lease ->
                renews = false
                lease.blocking { mutated = true }
            }
        }
        assertEquals(false, mutated)
    }

    @Test
    fun `a lease cannot resume mutations after it has reported ownership lost`() = runTest {
        var mutated = false
        withServingModelLock(factory) { lease ->
            renews = false
            assertFailsWith<IllegalStateException> { lease.ensureHeld() }
            renews = true
            assertFailsWith<IllegalStateException> { lease.blocking { mutated = true } }
        }
        assertEquals(false, mutated)
    }
}
