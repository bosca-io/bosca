package bosca.sharedqueue.jobs.redis

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.core.annotations.Internal
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import bosca.test.resources.SharedValkeyContainer
import bosca.db.ConnectionManager
import bosca.db.ConnectionManagerCallback
import bosca.db.asCoroutineContext
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests verifying the [RedisJobQueue] against a real Redis instance
 * managed by TestContainers.
 *
 * These tests exercise the full Redis-based job queue lifecycle including enqueue,
 * dequeue, completion, failure with retry, cancellation, and expired job recovery,
 * all using actual Redis data structures and Lua scripts.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisJobQueueEndToEndTest {

    private lateinit var redisContainer: SharedValkeyContainer
    private lateinit var redisPool: RedisConnectionPool
    private lateinit var lockFactory: RedisDistributedLockFactory
    private lateinit var queue: RedisJobQueue
    private val json = Json

    @OptIn(InternalDI::class)
    @BeforeTest
    fun setup() {
        redisContainer = SharedValkeyContainer()
        redisContainer.start()

        redisPool = redisContainer.newConnectionPool(maxConnections = 5)
        lockFactory = RedisDistributedLockFactory(redisPool)

        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }

        queue = RedisJobQueue("redis-e2e-test", redisPool, json, lockFactory)

        // Allow init GlobalScope coroutine to start
        Thread.sleep(1000)
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::redisContainer.isInitialized) redisContainer.stop()
    }

    private suspend fun readState(jobId: UUID): SerializedJob? {
        val connection = redisPool.connection()
        try {
            val data = connection.coroutines().hget(RedisValues.state("redis-e2e-test"), jobId.toString())
                ?: return null
            return json.decodeFromString(SerializedJob.serializer(), data)
        } finally {
            redisPool.release(connection)
        }
    }

    private suspend fun pendingCount(): Long {
        val connection = redisPool.connection()
        try {
            return connection.coroutines().llen(RedisValues.pending("redis-e2e-test")) ?: 0
        } finally {
            redisPool.release(connection)
        }
    }

    private suspend fun runningCount(): Long {
        val connection = redisPool.connection()
        try {
            return connection.coroutines().zcard(RedisValues.running("redis-e2e-test")) ?: 0
        } finally {
            redisPool.release(connection)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueue stores job state and pushes to pending list`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("""{"task": "test"}"""),
            executor = RedisE2ETestJobExecutor::class
        )

        val jobId = queue.enqueue(job)
        assertNotNull(jobId)

        val state = readState(jobId)
        assertNotNull(state, "Job state should exist in Redis hash after enqueue")
        assertEquals(JobStatus.PENDING, state.status)
        assertEquals(jobId, state.id)

        val pending = pendingCount()
        assertTrue(pending >= 1, "Pending list should have at least 1 job")
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent atomically preserves the first durable job`() = runBlocking {
        val jobId = UUID.random()
        val first = InternalJobConstructor(
            definition = Json.parseToJsonElement("""{"attempt":1}"""),
            executor = RedisE2ETestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }
        val duplicate = InternalJobConstructor(
            definition = Json.parseToJsonElement("""{"attempt":2}"""),
            executor = RedisE2ETestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }

        assertEquals(jobId, queue.enqueueIfAbsent(first))
        assertEquals(jobId, queue.enqueueIfAbsent(duplicate))

        assertEquals(1L, pendingCount(), "the persistent job ID is added to pending exactly once")
        val state = readState(jobId)
        assertNotNull(state)
        assertEquals(
            Json.parseToJsonElement("""{"attempt":1}"""),
            state.definition,
            "recovery must not replace existing durable state",
        )
    }

    @OptIn(Internal::class)
    @Test
    fun `dequeue retrieves job and moves to running set`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued, "Should dequeue the job")
        assertEquals(jobId, dequeued.getId())

        val running = runningCount()
        assertTrue(running >= 1, "Running set should have at least 1 job after dequeue")

        // State should still exist
        val state = readState(jobId)
        assertNotNull(state, "Job state should still exist after dequeue")

        // Clean up
        queue.markComplete(dequeued)
    }

    @OptIn(Internal::class)
    @Test
    fun `markComplete removes job state from Redis`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markComplete(dequeued)

        val state = readState(jobId)
        assertTrue(state == null, "Job state should be removed from Redis after markComplete")
    }

    @OptIn(Internal::class)
    @Test
    fun `markFailed with retry keeps state and increments failures`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markFailed(dequeued, RuntimeException("transient error"), retry = true)

        val state = readState(jobId)
        assertNotNull(state, "Job state should still exist for retryable failure")
        assertEquals(JobStatus.FAILED, state.status)
        assertEquals(1, state.failures, "Failure count should be 1")
    }

    @OptIn(Internal::class)
    @Test
    fun `markFailed without retry removes job state`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markFailed(dequeued, RuntimeException("permanent failure"), retry = false)

        val state = readState(jobId)
        assertTrue(state == null, "Job state should be removed for non-retryable failure")
    }

    @OptIn(Internal::class)
    @Test
    fun `markCancelled removes all traces from Redis`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        queue.markCancelled(jobId)

        val state = readState(jobId)
        assertTrue(state == null, "Job state should be removed after cancellation")
    }

    @OptIn(Internal::class)
    @Test
    fun `multiple jobs enqueue and complete lifecycle`() = runBlocking {
        val jobIds = mutableListOf<UUID>()
        repeat(3) { i ->
            val job = InternalJobConstructor(
                definition = Json.parseToJsonElement("""{"index": $i}"""),
                executor = RedisE2ETestJobExecutor::class
            )
            jobIds.add(queue.enqueue(job))
        }

        // All should have state
        for (id in jobIds) {
            val state = readState(id)
            assertNotNull(state, "Job $id should have state after enqueue")
            assertEquals(JobStatus.PENDING, state.status)
        }

        // Dequeue and complete all
        val dequeued = mutableListOf<bosca.sharedqueue.jobs.Job>()
        repeat(3) {
            val job = queue.dequeue()
            assertNotNull(job, "Should dequeue job #$it")
            dequeued.add(job)
        }

        for (job in dequeued) {
            queue.markComplete(job)
        }

        // All state should be cleaned up
        for (id in jobIds) {
            val state = readState(id)
            assertTrue(state == null, "Job $id state should be removed after complete")
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `expireAllJobs moves expired running jobs back to pending`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        // Dequeue to move to running
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        // Release the lock from the first dequeue to simulate the worker crashing
        dequeued.lock?.release()

        // expireAllJobs uses Long.MAX_VALUE, which makes ALL running jobs expire
        queue.expireAllJobs()

        // The job should now be back in the pending list
        val pending = pendingCount()
        assertTrue(pending >= 1, "Expired job should be moved back to pending list")

        // Clean up - dequeue and complete
        val reDequeued = queue.dequeue()
        assertNotNull(reDequeued, "Expired job should be dequeueable again")
        assertEquals(jobId, reDequeued.getId())
        queue.markComplete(reDequeued)
    }

    @OptIn(Internal::class)
    @Test
    fun `dequeue returns null when queue is empty`() = runBlocking {
        val dequeued = queue.dequeue()
        assertTrue(dequeued == null, "Dequeue should return null for empty queue")
    }

    @OptIn(Internal::class)
    @Test
    fun `checkin renews running set score and returns true`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class
        )
        queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        val result = queue.checkin(dequeued, 120_000)
        assertTrue(result, "Checkin should succeed for a held lock")

        // Clean up
        queue.markComplete(dequeued)
    }

    // ----- additional branch coverage -----

    private suspend fun deleteState(jobId: UUID) {
        val connection = redisPool.connection()
        try {
            connection.coroutines().hdel(RedisValues.state("redis-e2e-test"), jobId.toString())
        } finally {
            redisPool.release(connection)
        }
    }

    private suspend fun corruptState(jobId: UUID) {
        val connection = redisPool.connection()
        try {
            connection.coroutines().hset(RedisValues.state("redis-e2e-test"), mapOf(jobId.toString() to "not-json"))
        } finally {
            redisPool.release(connection)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueLater with a positive timeout schedules the job`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueueLater(job, kotlin.time.Duration.parse("60s"))

        val state = readState(jobId)
        assertNotNull(state, "scheduled job keeps its state")
        assertEquals(JobStatus.PENDING, state.status)
        assertTrue(runningCount() >= 1, "a delayed job is parked in the running set with a future score")
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueLater with a non-positive timeout enqueues immediately`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueueLater(job, kotlin.time.Duration.ZERO)

        val state = readState(jobId)
        assertNotNull(state)
        assertEquals(JobStatus.PENDING, state.status)
        assertTrue(pendingCount() >= 1, "a non-positive delay is treated as an immediate enqueue")
    }

    @OptIn(Internal::class)
    @Test
    fun `setDefinition updates the stored payload`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("""{"v":1}"""), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueue(job)
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        val newDefinition = Json.parseToJsonElement("""{"v":2}""")
        queue.setDefinition(dequeued, newDefinition)

        val state = readState(jobId)
        assertNotNull(state)
        assertEquals(newDefinition, state.definition)
        queue.markComplete(dequeued)
    }

    @OptIn(Internal::class)
    @Test
    fun `checkin returns false when the lock is not held`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        queue.enqueue(job)
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)
        dequeued.lock?.release()

        assertTrue(!queue.checkin(dequeued, 120_000), "checkin must fail once the lock is released")
    }

    @OptIn(Internal::class)
    @Test
    fun `markFailed reacquires the lock when it is not held`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueue(job)
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)
        // Simulate a lost lock so markFailed must reacquire via internalGetJob.
        dequeued.lock?.release()

        queue.markFailed(dequeued, RuntimeException("boom"), retry = true)

        val state = readState(jobId)
        assertNotNull(state, "retryable failure keeps state after reacquiring the lock")
        assertEquals(JobStatus.FAILED, state.status)
    }

    @OptIn(Internal::class)
    @Test
    fun `clearJobLock lets the lock be reacquired`() = runBlocking<Unit> {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueue(job)
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        // Force-release the held lock; getJob must then be able to acquire it.
        queue.clearJobLock(jobId)
        val fetched = queue.getJob(jobId) { it }
        assertNotNull(fetched, "job is reacquirable after its lock is force-released")
    }

    @OptIn(Internal::class)
    @Test
    fun `clearAllJobLocks force-releases every job lock`() = runBlocking<Unit> {
        val a = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class))
        val b = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class))

        queue.clearAllJobLocks()

        // Both remain gettable (the operation only clears locks, not state).
        assertNotNull(queue.getJob(a) { it })
        assertNotNull(queue.getJob(b) { it })
    }

    @OptIn(Internal::class)
    @Test
    fun `dequeue returns null when the job state has vanished`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueue(job)
        deleteState(jobId)

        assertTrue(queue.dequeue() == null, "a queued id whose state is gone is dropped, yielding null")
    }

    @OptIn(Internal::class)
    @Test
    fun `setJob fails for a job that carries no lock`() = runBlocking<Unit> {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
            .apply { setId(UUID.random()) } // no lock attached
        assertFailsWith<IllegalStateException> { queue.setJob(job) }
    }

    @OptIn(Internal::class)
    @Test
    fun `setJob fails when the lock is present but no longer held`() = runBlocking<Unit> {
        val jobId = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class))
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)
        dequeued.lock?.release() // lock object stays attached but is no longer held
        assertFailsWith<IllegalStateException> { queue.setJob(dequeued) }
    }

    @OptIn(Internal::class)
    @Test
    fun `checkin returns false for a job that never had a lock`() = runBlocking<Unit> {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
            .apply { setId(UUID.random()) }
        assertTrue(!queue.checkin(job, 120_000))
    }

    @OptIn(Internal::class)
    @Test
    fun `getJob errors when the job state does not exist`() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { queue.getJob(UUID.random()) { it } }
    }

    @OptIn(Internal::class)
    @Test
    fun `markComplete reacquires the lock when it is not held`() = runBlocking<Unit> {
        val jobId = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class))
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)
        dequeued.lock?.release()

        queue.markComplete(dequeued)

        assertTrue(readState(jobId) == null, "state is removed after reacquiring and completing")
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueue inside a transaction defers the write until commit`() = runBlocking<Unit> {
        val cm = mockk<ConnectionManager>(relaxed = true)
        every { cm.inTransaction } returns true
        val callback = slot<ConnectionManagerCallback>()
        every { cm.addCallback(capture(callback)) } just Runs

        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = withContext(cm.asCoroutineContext()) { queue.enqueue(job) }

        assertTrue(readState(jobId) == null, "the write is deferred until the transaction commits")
        callback.captured.onCommit()
        callback.captured.onRelease()
        assertNotNull(readState(jobId), "the commit callback performs the real enqueue")
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueue runs immediately when the connection has no active transaction`() = runBlocking<Unit> {
        val cm = mockk<ConnectionManager>(relaxed = true)
        every { cm.inTransaction } returns false
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = withContext(cm.asCoroutineContext()) { queue.enqueue(job) }
        assertNotNull(readState(jobId), "no active transaction → the enqueue runs right away")
    }

    @OptIn(Internal::class)
    @Test
    fun `markFailed becomes terminal once max failures are reached`() = runBlocking<Unit> {
        // maxFailures = 1 → the first retryable failure exhausts retries and is terminal.
        val job = bosca.sharedqueue.jobs.Job(
            definition = Json.parseToJsonElement("{}"),
            executor = RedisE2ETestJobExecutor::class,
            maxFailures = 1,
        )
        val jobId = queue.enqueue(job)
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markFailed(dequeued, RuntimeException("boom"), retry = true)

        assertTrue(readState(jobId) == null, "state is removed once retries are exhausted, even with retry=true")
    }

    @OptIn(Internal::class)
    @Test
    fun `dequeue pushes back to pending when the state cannot be deserialized`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), RedisE2ETestJobExecutor::class)
        val jobId = queue.enqueue(job)
        corruptState(jobId)

        // Deserialization throws inside dequeue → the id is pushed back to pending and null returned.
        assertTrue(queue.dequeue() == null)
        assertTrue(pendingCount() >= 1, "the un-deserializable job is returned to pending, not dropped")
    }
}

class RedisE2ETestJobExecutor : JobExecutor {
    override suspend fun execute() {}
}
