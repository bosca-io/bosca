package bosca.sharedqueue.jobs.nats

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.core.annotations.Internal
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.mockk
import io.nats.client.KeyValue
import io.nats.client.api.KeyValueConfiguration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NatsJobQueueEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var lockFactory: NatsDistributedLockFactory
    private lateinit var queue: NatsJobQueue
    private lateinit var kv: KeyValue
    private val json = Json

    @OptIn(InternalDI::class)
    @BeforeTest
    fun setup() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())
        natsContainer.start()

        natsPool = natsContainer.newConnectionPool(1)
        lockFactory = NatsDistributedLockFactory(natsPool)

        // DI providers needed by callback execution (mocked - we're testing queue state, not callbacks)
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }

        // Disable the init-block background sweep loop. It runs on a GlobalScope/JobsDispatcher
        // coroutine that teardown never cancels, so it outlives this test's connection and, once
        // the container/connection goes away, throws uncaught exceptions that leak into later
        // tests' runTest blocks (surfacing as UncaughtExceptionsBeforeTest) and pile connection
        // load onto NATS. These tests drive expiry directly via expireAllJobs()/checkForExpiredJobs(),
        // so the loop isn't needed here.
        queue = NatsJobQueue("e2e-test", natsPool, json, lockFactory, processExpiredJobs = false)

        // Allow stream and KV store creation to complete before the tests interact with them.
        runBlocking { queue.expireAllJobs() }
        Thread.sleep(1000)

        kv = runBlocking { natsPool.systemConnection() }.keyValue("job-state-e2e-test")
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    private fun readKvEntry(jobId: UUID): SerializedJob? {
        val entry = kv.get(jobId.toString()) ?: return null
        val value = entry.valueAsString ?: return null
        return json.decodeFromString(SerializedJob.serializer(), value)
    }

    @Test
    fun `existing job buckets gain bounded markers without purging legacy tombstones`() = runBlocking {
        val queueName = "legacy-${UUID.random().toString().substring(0, 8)}"
        val stateBucket = "job-state-$queueName"
        val scheduledBucket = "job-scheduled-$queueName"
        val connection = natsPool.systemConnection()
        val management = connection.keyValueManagement()
        management.create(KeyValueConfiguration.builder().name(stateBucket).build())
        management.create(KeyValueConfiguration.builder().name(scheduledBucket).build())
        val state = connection.keyValue(stateBucket)
        val scheduled = connection.keyValue(scheduledBucket)
        state.put("old-job", "state")
        state.delete("old-job")
        scheduled.put("old-job", "scheduled")
        scheduled.delete("old-job")

        val legacyQueue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = false)
        legacyQueue.checkForExpiredJobs(Long.MAX_VALUE)

        assertEquals(java.time.Duration.ofMinutes(5), management.getStatus(stateBucket).limitMarkerTtl)
        assertEquals(java.time.Duration.ofMinutes(5), management.getStatus(scheduledBucket).limitMarkerTtl)
        assertEquals(1L, management.getStatus(stateBucket).entryCount)
        assertEquals(1L, management.getStatus(scheduledBucket).entryCount)
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueue creates KV entry with PENDING status`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )

        val jobId = queue.enqueue(job)
        assertNotNull(jobId)

        val serialized = readKvEntry(jobId)
        assertNotNull(serialized, "KV entry should exist after enqueue")
        assertEquals(JobStatus.PENDING, serialized.status, "Job should be PENDING after enqueue")
        assertEquals(jobId, serialized.id)
    }

    @OptIn(Internal::class)
    @Test
    fun `dequeue retrieves job and KV entry still exists`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued, "Should dequeue the job")
        assertEquals(jobId, dequeued.getId())

        // KV entry should still exist (removed only on markComplete)
        val serialized = readKvEntry(jobId)
        assertNotNull(serialized, "KV entry should still exist after dequeue")
        // Dequeue sets RUNNING in KV so processExpiredJobs can recover stale jobs
        assertEquals(JobStatus.RUNNING, serialized.status, "KV status should be RUNNING after dequeue")
    }

    @OptIn(Internal::class)
    @Test
    fun `markComplete removes KV entry for fully complete job`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markComplete(dequeued)

        val serialized = readKvEntry(jobId)
        assertTrue(serialized == null, "KV entry should be deleted after markComplete")
    }

    @OptIn(Internal::class)
    @Test
    fun `markFailed with retry keeps KV entry with FAILED status`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markFailed(dequeued, RuntimeException("test failure"), retry = true)

        val serialized = readKvEntry(jobId)
        assertNotNull(serialized, "KV entry should still exist for retryable failed job")
        assertEquals(JobStatus.FAILED, serialized.status, "Status should be FAILED")
        assertEquals(1, serialized.failures, "Failure count should be 1")
    }

    @OptIn(Internal::class)
    @Test
    fun `markFailed without retry removes KV entry`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        queue.markFailed(dequeued, RuntimeException("permanent failure"), retry = false)

        val serialized = readKvEntry(jobId)
        assertTrue(serialized == null, "KV entry should be deleted for non-retryable failed job")
    }

    @OptIn(Internal::class)
    @Test
    fun `multiple jobs enqueue and complete lifecycle`() = runBlocking {
        val ids = mutableListOf<UUID>()
        repeat(3) { i ->
            val job = InternalJobConstructor(
                definition = Json.parseToJsonElement("""{"index": $i}"""),
                executor = E2ETestJobExecutor::class
            )
            ids.add(queue.enqueue(job))
        }

        // All should be in KV as PENDING
        for (id in ids) {
            val serialized = readKvEntry(id)
            assertNotNull(serialized, "Job $id should be in KV after enqueue")
            assertEquals(JobStatus.PENDING, serialized.status)
        }

        // Dequeue and complete all
        val dequeued = mutableListOf<bosca.sharedqueue.jobs.Job>()
        repeat(3) {
            val job = queue.dequeue()
            assertNotNull(job, "Should dequeue job $it")
            dequeued.add(job)
        }

        for (job in dequeued) {
            queue.markComplete(job)
        }

        // All should be removed from KV
        for (id in ids) {
            val serialized = readKvEntry(id)
            assertTrue(serialized == null, "Job $id should be removed from KV after complete")
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `processExpiredJobs re-publishes stale RUNNING jobs`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        // Dequeue to consume the JetStream message
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        // Manually write a RUNNING job with an old modified timestamp to KV
        val entry = kv.get(jobId.toString())
        assertNotNull(entry)
        val serialized = json.decodeFromString(SerializedJob.serializer(), entry.valueAsString!!)
        val oldModified = bosca.serialization.OffsetDateTime.now().minusHours(2)
        val staleRunningJob = SerializedJob(
            id = serialized.id,
            parentId = serialized.parentId,
            type = serialized.type,
            status = JobStatus.RUNNING,
            failures = serialized.failures,
            maxFailures = serialized.maxFailures,
            created = serialized.created,
            modified = oldModified,
            definition = serialized.definition,
            executor = serialized.executor,
            executorName = serialized.executorName,
            children = serialized.children,
            callbacks = serialized.callbacks,
            context = serialized.context
        )
        kv.put(jobId.toString(), json.encodeToString(SerializedJob.serializer(), staleRunningJob))

        // ACK the original dequeued message and release the lock so the consumer is clear
        (dequeued.message as? io.nats.client.Message)?.ack()
        dequeued.lock?.release()

        // Run expired job check - should re-publish the stale RUNNING job
        queue.expireAllJobs()

        // The KV entry should still exist (processExpiredJobs only re-publishes the JetStream message)
        val afterSerialized = readKvEntry(jobId)
        assertNotNull(afterSerialized, "KV entry should still exist after re-publish")
        assertEquals(JobStatus.PENDING, afterSerialized.status)

        // The re-published message should be dequeueable
        val reDequeued = queue.dequeue()
        assertNotNull(reDequeued, "Stale RUNNING job should be re-dequeueable after expireAllJobs")
        assertEquals(jobId, reDequeued.getId())

        // Clean up
        queue.markComplete(reDequeued)
    }

    @OptIn(Internal::class)
    @Test
    fun `processExpiredJobs re-publishes stale PENDING jobs`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        // Dequeue and ACK to consume the JetStream message
        val dequeued = queue.dequeue()
        assertNotNull(dequeued)
        queue.markComplete(dequeued)

        // Manually put a stale PENDING entry back in KV (simulating orphaned state
        // where the JetStream message was consumed but the job was never executed)
        val oldModified = bosca.serialization.OffsetDateTime.now().minusHours(2)
        val stalePendingJob = SerializedJob(
            id = jobId,
            parentId = null,
            type = "bosca.sharedqueue.jobs.Job",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = oldModified,
            modified = oldModified,
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )
        kv.put(jobId.toString(), json.encodeToString(SerializedJob.serializer(), stalePendingJob))

        // Run expired job check - should re-publish stale PENDING jobs
        queue.expireAllJobs()

        // KV entry should still exist (processExpiredJobs re-publishes, doesn't delete)
        val afterSerialized = readKvEntry(jobId)
        assertNotNull(afterSerialized, "Stale PENDING job should still be in KV after re-publish")
        assertEquals(JobStatus.PENDING, afterSerialized.status)

        // The re-published message should be dequeueable
        val reDequeued = queue.dequeue()
        assertNotNull(reDequeued, "Stale PENDING job should be re-dequeueable after expireAllJobs")
        assertEquals(jobId, reDequeued.getId())

        // After dequeue, status should now be RUNNING
        val afterDequeue = readKvEntry(jobId)
        assertNotNull(afterDequeue)
        assertEquals(JobStatus.RUNNING, afterDequeue.status)

        // Clean up
        queue.markComplete(reDequeued)
    }

    @OptIn(Internal::class)
    @Test
    fun `cancelled job is cleaned up and dequeue skips it`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        // Cancel the job (removes KV entry)
        queue.markCancelled(jobId)

        val serialized = readKvEntry(jobId)
        assertTrue(serialized == null, "KV entry should be deleted after cancellation")

        // The JetStream message is still in the stream, but dequeue should handle
        // the missing KV entry gracefully
        val dequeued = queue.dequeue()
        // Dequeue should return null because the KV state was removed (cancelled)
        assertTrue(dequeued == null, "Dequeue should return null for cancelled job (KV entry missing)")
    }

    @OptIn(Internal::class)
    @Test
    fun `checkin renews lock and updates KV state`() = runBlocking {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = E2ETestJobExecutor::class
        )
        val jobId = queue.enqueue(job)

        val dequeued = queue.dequeue()
        assertNotNull(dequeued)

        // Model a long-running job that needs check-in to refresh scanner liveness.
        dequeued.modified = bosca.serialization.OffsetDateTime.now().minusHours(1)
        queue.setJob(dequeued)

        val beforeCheckin = readKvEntry(jobId)
        assertNotNull(beforeCheckin)
        assertEquals(JobStatus.RUNNING, beforeCheckin.status)

        val checkedIn = queue.checkin(dequeued, 120_000)
        assertTrue(checkedIn, "Checkin should succeed")

        val afterCheckin = readKvEntry(jobId)
        assertNotNull(afterCheckin, "KV entry should still exist after checkin")
        assertEquals(JobStatus.RUNNING, afterCheckin.status)
        assertTrue(
            afterCheckin.modified > beforeCheckin.modified,
            "Successful check-in must advance the persisted liveness timestamp"
        )
        val messagesBeforeScan = natsPool.systemConnection().jetStreamManagement()
            .getStreamInfo("jobs-e2e-test").streamState.msgCount
        queue.checkForExpiredJobs(System.currentTimeMillis())
        assertEquals(JobStatus.RUNNING, readKvEntry(jobId)?.status)
        assertEquals(
            messagesBeforeScan,
            natsPool.systemConnection().jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount,
            "The scanner must not republish a healthy running job"
        )

        // Clean up
        queue.markComplete(dequeued)
    }

    /**
     * the release-relay orphaning, replayed against the real queue + listener:
     * a parent (run-job stand-in) with an attached child completes its delivery; the child then
     * completes ITS delivery while a grandchild (backing work) is still open. Before the fix, the
     * child's completion notify recorded it COMPLETE in the parent's embedded snapshot (which never
     * sees grandchildren), the parent read fully complete, and its state was DELETED while the
     * grandchild was live — orphaning later notifies and the bounded iteration's next-item attach.
     * The parent must survive until the grandchild settles, then the cascade closes everything.
     */
    @OptIn(Internal::class, InternalDI::class)
    @Test
    fun `parent survives a child completing with open grandchildren and closes on the settled cascade`(): Unit = runBlocking {
        provides<bosca.sharedqueue.jobs.listeners.NotifyParentListener> {
            bosca.sharedqueue.jobs.listeners.NotifyParentListener()
        }

        // Parent (the run job): enqueue, dequeue (locked), attach the child mid-"execution",
        // enqueue the child, then complete the delivery — children open, state must be kept.
        val parent = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
        val parentId = queue.enqueue(parent)
        val runningParent = queue.dequeue()
        assertNotNull(runningParent)
        assertEquals(parentId, runningParent.getId())

        val child = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
        runningParent.addChild(child, runOnParentComplete = false)
        val childId = queue.enqueue(child)
        withContext(queue.asCoroutineContext(runningParent)) {
            queue.markComplete(runningParent)
        }
        assertNotNull(readKvEntry(parentId), "parent state must be kept while its child is open")

        // Child (the item-0 child run job): dequeue, attach a grandchild (its backing work),
        // enqueue it, complete the delivery. The child's notify must NOT close the parent.
        val runningChild = queue.dequeue()
        assertNotNull(runningChild)
        assertEquals(childId, runningChild.getId())

        val grandchild = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
        runningChild.addChild(grandchild, runOnParentComplete = false)
        val grandchildId = queue.enqueue(grandchild)
        withContext(queue.asCoroutineContext(runningChild)) {
            queue.markComplete(runningChild)
        }
        assertNotNull(readKvEntry(childId), "child state must be kept while its grandchild is open")
        assertNotNull(
            readKvEntry(parentId),
            "parent state must survive a child that completed its delivery with an open grandchild",
        )

        // Grandchild settles: the cascade closes child then parent, in order, via the listener.
        val runningGrandchild = queue.dequeue()
        assertNotNull(runningGrandchild)
        assertEquals(grandchildId, runningGrandchild.getId())
        withContext(queue.asCoroutineContext(runningGrandchild)) {
            queue.markComplete(runningGrandchild)
        }
        assertTrue(readKvEntry(grandchildId) == null, "settled grandchild state is removed")
        assertTrue(readKvEntry(childId) == null, "the settled cascade closes the child")
        assertTrue(readKvEntry(parentId) == null, "the settled cascade closes the parent last")
    }
}

class E2ETestJobExecutor : JobExecutor {
    override suspend fun execute() {}
}
