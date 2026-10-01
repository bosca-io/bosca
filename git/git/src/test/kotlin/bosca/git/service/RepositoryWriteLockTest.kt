package bosca.git.service

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Branch coverage for [withRepositoryWriteLock] and [RepositoryWriteLock] using an
 * in-memory lock whose holder map gives real cross-instance mutual-exclusion
 * semantics — so the tests exercise the actual acquire/renew/release/contention
 * behaviour, not just stubbed return values.
 */
class RepositoryWriteLockTest {

    /** Shared holder map = "the backing store". A name is held while it maps to a token. */
    private class InMemoryLockFactory : DistributedLockFactory {
        val holders = ConcurrentHashMap<String, Any>()
        val failRenew = AtomicBoolean(false)

        /** Renewals throw the backend's own timeout (a CancellationException), as an exhausted pool does. */
        val renewTimesOut = AtomicBoolean(false)

        /** Renewals fail with a backend error (a plain exception, not a cancellation). */
        val renewThrows = AtomicBoolean(false)

        /** Renewals take this long before answering. */
        var renewDelayMillis = 0L
        val created = mutableListOf<InMemoryLock>()

        override suspend fun create(name: String): DistributedLock =
            InMemoryLock(name, holders, failRenew, renewTimesOut, renewThrows) { renewDelayMillis }.also { created += it }

        override suspend fun forceRelease(name: String): Boolean = holders.remove(name) != null
    }

    private class InMemoryLock(
        val name: String,
        private val holders: ConcurrentHashMap<String, Any>,
        private val failRenew: AtomicBoolean,
        private val renewTimesOut: AtomicBoolean,
        private val renewThrows: AtomicBoolean,
        private val renewDelayMillis: () -> Long,
    ) : DistributedLock {
        private val token = Any()
        var renewCount = 0
            private set

        override val isHeld: Boolean get() = holders[name] === token

        override suspend fun tryAcquire(ttlMillis: Long): Boolean =
            holders.putIfAbsent(name, token) == null

        override suspend fun acquire(ttlMillis: Long, waitTimeoutMillis: Long?, retryDelayMillis: Long): Boolean {
            if (tryAcquire(ttlMillis)) return true
            var waited = 0L
            while (waitTimeoutMillis == null || waited < waitTimeoutMillis) {
                delay(retryDelayMillis)
                waited += retryDelayMillis
                if (tryAcquire(ttlMillis)) return true
            }
            return false
        }

        override suspend fun renew(ttlMillis: Long): Boolean {
            renewCount++
            if (renewTimesOut.get()) withTimeout(1) { awaitCancellation() }
            if (renewThrows.get()) throw IllegalStateException("lock backend unavailable")
            delay(renewDelayMillis())
            if (failRenew.get()) {
                holders.remove(name, token)
                return false
            }
            return isHeld
        }

        override suspend fun release(): Boolean {
            yield()
            return holders.remove(name, token)
        }

        override suspend fun <T> withLock(
            ttlMillis: Long,
            waitTimeoutMillis: Long?,
            retryDelayMillis: Long,
            block: suspend () -> T,
        ): T? = throw NotImplementedError("unused by withRepositoryWriteLock")
    }

    private val repositoryId = UUID.random()

    @Test
    fun `key is stable and repository-scoped`() {
        assertEquals("git-repo-write-$repositoryId", RepositoryWriteLock.key(repositoryId))
    }

    @Test
    fun `runs the block, returns its result, and releases when acquired`() = runTest {
        val factory = InMemoryLockFactory()

        val result = factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) { "block-result" }

        assertEquals("block-result", result)
        assertTrue(factory.holders.isEmpty(), "lock must be released after the block")
    }

    @Test
    fun `returns null and skips the block when the lock is already held`() = runTest {
        val factory = InMemoryLockFactory()
        // Simulate another holder occupying the lock.
        factory.holders[RepositoryWriteLock.key(repositoryId)] = Any()
        var ran = false

        val result = factory.withRepositoryWriteLock(repositoryId, waitTimeoutMillis = 250) { ran = true }

        assertNull(result)
        assertFalse(ran, "block must not run when the lock cannot be acquired")
    }

    @Test
    fun `releases the lock even when the block throws`() = runTest {
        val factory = InMemoryLockFactory()

        assertFailsWith<IllegalStateException> {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                error("boom")
            }
        }

        assertTrue(factory.holders.isEmpty(), "lock must be released after a failing block")
    }

    @Test
    fun `cancelling a writer releases its lock before another writer starts`() = runTest {
        val factory = InMemoryLockFactory()
        val entered = CompletableDeferred<Unit>()
        val writer = launch {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()

        writer.cancelAndJoin()

        assertTrue(writer.isCancelled)
        assertTrue(factory.holders.isEmpty(), "cancellation must complete the suspending release")
        assertEquals("next writer", factory.withRepositoryWriteLock(repositoryId, 0) { "next writer" })
    }

    @Test
    fun `keeps renewing after cancellation until the block returns`() = runTest {
        val factory = InMemoryLockFactory()
        val entered = CompletableDeferred<Unit>()
        val writer = launch {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                entered.complete(Unit)
                // Like JGit's blocking receive, the work does not stop when the push is cancelled;
                // the lock must not expire under it.
                withContext(NonCancellable) { delay(RepositoryWriteLock.RENEW_INTERVAL_MILLIS * 2 + 1_000) }
            }
        }
        entered.await()

        writer.cancel()
        writer.join()

        assertTrue(factory.created.single().renewCount >= 2, "The lock must stay renewed while the block still runs")
        assertTrue(factory.holders.isEmpty(), "The lock is released once the block returns")
    }

    @Test
    fun `a renewal that times out counts as losing the lock`() = runTest {
        val factory = InMemoryLockFactory()
        factory.renewTimesOut.set(true)

        assertFailsWith<RepositoryWriteLockLostException> {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                delay(RepositoryWriteLock.RENEW_INTERVAL_MILLIS * 2 + 1_000)
            }
        }
        assertTrue(factory.holders.isEmpty())
    }

    @Test
    fun `a renewal that fails after the block finished does not fail the finished operation`() = runTest {
        val factory = InMemoryLockFactory()
        // The renewal starts while the block runs and answers "not held" only after it finished.
        factory.renewDelayMillis = 5_000
        factory.failRenew.set(true)

        val result = factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
            delay(RepositoryWriteLock.RENEW_INTERVAL_MILLIS + 100)
            "done"
        }

        assertEquals("done", result)
    }

    @Test
    fun `renews the lock across a long-running block`() = runTest {
        val factory = InMemoryLockFactory()

        val result = factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
            // Span more than two renewal intervals so the renewer fires repeatedly.
            delay(RepositoryWriteLock.RENEW_INTERVAL_MILLIS * 2 + 1_000)
        }

        assertNotNull(result)
        assertTrue(factory.created.single().renewCount >= 2, "renewer should have fired at least twice")
        assertTrue(factory.holders.isEmpty())
    }

    @Test
    fun `a renewal that throws counts as losing the lock`() = runTest {
        val factory = InMemoryLockFactory()
        factory.renewThrows.set(true)

        assertFailsWith<RepositoryWriteLockLostException> {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                delay(RepositoryWriteLock.RENEW_INTERVAL_MILLIS * 2 + 1_000)
            }
        }
        assertTrue(factory.holders.isEmpty())
    }

    @Test
    fun `renewals keep running while the caller's own thread is blocked`() = runTest {
        val factory = InMemoryLockFactory()
        val callerThread = Executors.newSingleThreadExecutor()
        val blockStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val writer = launch(callerThread.asCoroutineDispatcher()) {
                factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                    blockStarted.countDown()
                    // Blocks the caller's only thread, as JGit work on a saturated pool would.
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            // The block starts only once the renewer is waiting for its first interval.
            assertTrue(blockStarted.await(5, TimeUnit.SECONDS))

            repeat(2) {
                testScheduler.advanceTimeBy(RepositoryWriteLock.RENEW_INTERVAL_MILLIS)
                testScheduler.runCurrent()
            }

            assertEquals(2, factory.created.single().renewCount, "Renewals must not wait for the caller's thread")
            release.countDown()
            writer.join()
            assertTrue(factory.holders.isEmpty())
        } finally {
            release.countDown()
            callerThread.shutdownNow()
        }
    }

    @Test
    fun `aborts with lock-lost when renewal fails mid-operation`() = runTest {
        val factory = InMemoryLockFactory()
        factory.failRenew.set(true)

        assertFailsWith<RepositoryWriteLockLostException> {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) {
                delay(RepositoryWriteLock.RENEW_INTERVAL_MILLIS + 1_000)
                error("block should have been cancelled before reaching here")
            }
        }

        assertTrue(factory.holders.isEmpty(), "lock must be released even when renewal fails")
    }

    @Test
    fun `serializes writers on the same repository`() = runTest {
        val factory = InMemoryLockFactory()
        val key = RepositoryWriteLock.key(repositoryId)

        // First holder takes the lock and keeps it.
        assertTrue(factory.create(key).tryAcquire(RepositoryWriteLock.TTL_MILLIS))

        // A writer for the same repository cannot acquire within its wait budget.
        val result = factory.withRepositoryWriteLock(repositoryId, waitTimeoutMillis = 250) { }
        assertNull(result)
    }

    @Test
    fun `does not contend across different repositories`() = runTest {
        val factory = InMemoryLockFactory()
        val other = UUID.random()
        // Hold a different repository's lock.
        factory.holders[RepositoryWriteLock.key(other)] = Any()
        var ran = false

        val result = factory.withRepositoryWriteLock(repositoryId, waitTimeoutMillis = 250) { ran = true }

        assertNotNull(result)
        assertTrue(ran)
    }

    @Test
    fun `lock-lost exception names the lock`() {
        val ex = RepositoryWriteLockLostException("git-repo-write-abc")
        assertTrue(ex.message!!.contains("git-repo-write-abc"))
    }

    @Test
    fun `busy exception names the repository`() {
        val ex = RepositoryWriteBusyException(repositoryId)
        assertTrue(ex.message!!.contains(repositoryId.toString()))
    }

    // ── ensureHeld: the fence for blocking (uncancellable) critical sections ──

    @Test
    fun `ensureHeld passes while the lock is held and re-verifies with the backend`() = runTest {
        val factory = InMemoryLockFactory()

        val result = factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) { handle ->
            handle.ensureHeld()
            "committed"
        }

        assertEquals("committed", result)
        // The fence's ownership check is a real renew round trip, not a local flag read.
        assertTrue(factory.created.single().renewCount >= 1, "ensureHeld must renew against the backend")
    }

    @Test
    fun `ensureHeld throws when the backend no longer recognizes the holder`() = runTest {
        val factory = InMemoryLockFactory()

        assertFailsWith<RepositoryWriteLockLostException> {
            factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) { handle ->
                // The backend fails over / the TTL lapses between renewals: the very
                // next fence check must abort the swap even though the renewer has
                // not fired yet.
                factory.failRenew.set(true)
                handle.ensureHeld()
                error("the pack swap must not run once exclusivity is gone")
            }
        }

        assertTrue(factory.holders.isEmpty())
    }

    @Test
    fun `ensureHeld fails fast once marked lost without another backend call`() = runTest {
        val factory = InMemoryLockFactory()

        val result = factory.withRepositoryWriteLock(repositoryId, 1_000, StandardTestDispatcher(testScheduler)) { handle ->
            factory.failRenew.set(true)
            assertFailsWith<RepositoryWriteLockLostException> { handle.ensureHeld() }
            val renewsAfterLoss = factory.created.single().renewCount
            // Second fence check short-circuits on the lost flag.
            assertFailsWith<RepositoryWriteLockLostException> { handle.ensureHeld() }
            assertEquals(renewsAfterLoss, factory.created.single().renewCount)
            "caller-handled"
        }

        // The block chose to handle the loss itself and completed normally.
        assertEquals("caller-handled", result)
    }
}
