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
import bosca.sharedqueue.jobs.serialize
import io.mockk.every
import io.mockk.mockk
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.KeyValue
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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

    @OptIn(Internal::class)
    @Test
    fun `recovery does not duplicate a delivery still waiting in the stream`() = runBlocking {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
        val id = queue.enqueue(job)
        val management = natsPool.systemConnection().jetStreamManagement()

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        assertEquals(1L, management.getStreamInfo("jobs-e2e-test").streamState.msgCount)
        val dequeued = assertNotNull(queue.dequeue())
        assertEquals(id, dequeued.getId())
        queue.markComplete(dequeued)
    }

    @OptIn(Internal::class)
    @Test
    fun `delaying a delivered job retains its existing stream message`() = runBlocking {
        val id = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class))
        val dequeued = assertNotNull(queue.dequeue())
        val sequence = (dequeued.message as io.nats.client.Message).metaData().streamSequence()

        queue.enqueueLater(dequeued, 100.milliseconds)
        dequeued.lock?.release()

        val retried = assertNotNull(queue.dequeue())
        assertEquals(id, retried.getId())
        assertEquals(sequence, (retried.message as io.nats.client.Message).metaData().streamSequence())
        queue.markComplete(retried)
    }

    @OptIn(Internal::class)
    @Test
    fun `completed parent execution is not repeated while its children are pending`() = runBlocking {
        val parent = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
        parent.addChild(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class), runOnParentComplete = false)
        val id = queue.enqueue(parent)
        val running = assertNotNull(queue.dequeue())
        val connection = natsPool.systemConnection()
        connection.jetStream().publish("jobs.e2e-test", id.toString().toByteArray())
        queue.markComplete(running)
        val before = assertNotNull(kv.get(id.toString()))

        assertNull(queue.dequeue(100.milliseconds))
        connection.flush(java.time.Duration.ofSeconds(5))

        assertEquals(0L, connection.jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount)
        assertEquals(before.revision, kv.get(id.toString())?.revision)
        assertEquals(JobStatus.COMPLETE, readKvEntry(id)?.status)
    }

    @OptIn(Internal::class)
    @Test
    fun `recovered delivery survives dequeue before publication is confirmed`() = runBlocking {
        val connection = natsPool.systemConnection()
        val jetStream = connection.jetStream()
        val publishing = mockk<JetStream>(relaxed = true)
        lateinit var recovering: NatsJobQueue
        every { publishing.subscribe(any<String>(), any<PullSubscribeOptions>()) } answers {
            jetStream.subscribe(firstArg<String>(), secondArg<PullSubscribeOptions>())
        }
        every { publishing.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) } answers {
            val published = jetStream.publish(firstArg<String>(), secondArg<ByteArray>(), thirdArg<PublishOptions>())
            runBlocking { recovering.dequeue(50.milliseconds) }
            published
        }
        val pool = poolWith(publishing)
        try {
            recovering = NatsJobQueue("e2e-test", pool, json, lockFactory, processExpiredJobs = false)
            val job = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
            val unpublished = job.serialize().copy(status = JobStatus.UNINITIALIZED)
            kv.put(job.id.toString(), json.encodeToString(SerializedJob.serializer(), unpublished))

            recovering.checkForExpiredJobs(Long.MAX_VALUE)
            connection.flush(java.time.Duration.ofSeconds(5))

            assertEquals(JobStatus.PENDING, readKvEntry(job.id)?.status)
            assertEquals(1L, connection.jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount)
        } finally {
            pool.close()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `recovery replaces missing deliveries repeatedly within the deduplication window`() = runBlocking {
        val id = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class))
        val management = natsPool.systemConnection().jetStreamManagement()
        repeat(2) {
            val previous = management.getStreamInfo("jobs-e2e-test").streamState.lastSequence
            management.deleteMessage("jobs-e2e-test", previous)

            queue.expireAllJobs()

            val replacement = management.getStreamInfo("jobs-e2e-test").streamState.lastSequence
            assertTrue(replacement > previous)
            assertEquals(1L, management.getStreamInfo("jobs-e2e-test").streamState.msgCount)
        }
        queue.markComplete(assertNotNull(queue.dequeue()))
    }

    @OptIn(Internal::class)
    @Test
    fun `recovery leaves a locked running job unchanged`() = runBlocking {
        val id = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class))
        val running = assertNotNull(queue.dequeue())
        val before = assertNotNull(kv.get(id.toString()))

        queue.expireAllJobs()

        assertEquals(before.revision, kv.get(id.toString())?.revision)
        assertEquals(JobStatus.RUNNING, readKvEntry(id)?.status)
        queue.markComplete(running)
    }

    @OptIn(Internal::class)
    @Test
    fun `redelivery of the same running message is retained until its owner completes`() = runBlocking {
        val id = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class))
        val running = assertNotNull(queue.dequeue())
        (running.message as io.nats.client.Message).nak()

        assertNull(queue.dequeue(100.milliseconds))

        val connection = natsPool.systemConnection()
        connection.flush(java.time.Duration.ofSeconds(5))
        assertEquals(1L, connection.jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount)
        assertEquals(JobStatus.RUNNING, readKvEntry(id)?.status)
        queue.markComplete(running)
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent retains the original definition and delivery`() = runBlocking {
        val id = UUID.random()
        val original = InternalJobConstructor(Json.parseToJsonElement("""{"attempt":1}"""), E2ETestJobExecutor::class)
        original.setPersistentId(id)
        queue.enqueueIfAbsent(original)
        val before = assertNotNull(readKvEntry(id))
        val second = InternalJobConstructor(Json.parseToJsonElement("""{"attempt":2}"""), E2ETestJobExecutor::class)
        second.setPersistentId(id)

        queue.enqueueIfAbsent(second)

        assertEquals(before, readKvEntry(id))
        assertEquals(1L, natsPool.systemConnection().jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount)
        queue.markComplete(assertNotNull(queue.dequeue()))
    }

    private suspend fun poolWith(publishing: JetStream, state: KeyValue = kv): NatsConnectionPool {
        val real = natsPool.systemConnection()
        val connection = mockk<Connection>(relaxed = true)
        every { connection.jetStream() } returns publishing
        every { connection.jetStreamManagement() } returns real.jetStreamManagement()
        every { connection.keyValueManagement() } returns real.keyValueManagement()
        every { connection.keyValue(any<String>()) } answers {
            if (firstArg<String>() == "job-state-e2e-test") state else real.keyValue(firstArg<String>())
        }
        return NatsConnectionPool(connection)
    }

    @OptIn(Internal::class)
    @Test
    fun `consumer progress before publication confirmation is preserved`() = runBlocking {
        val real = natsPool.systemConnection().jetStream()
        val publishing = mockk<JetStream>(relaxed = true)
        lateinit var producing: NatsJobQueue
        var delivered: bosca.sharedqueue.jobs.Job? = null
        every { publishing.subscribe(any<String>(), any<PullSubscribeOptions>()) } answers {
            real.subscribe(firstArg<String>(), secondArg<PullSubscribeOptions>())
        }
        every { publishing.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) } answers {
            val result = real.publish(firstArg<String>(), secondArg<ByteArray>(), thirdArg<PublishOptions>())
            delivered = runBlocking { producing.dequeue(100.milliseconds) }
            result
        }
        val pool = poolWith(publishing)
        try {
            producing = NatsJobQueue("e2e-test", pool, json, lockFactory, processExpiredJobs = false)
            val id = producing.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class))

            assertEquals(JobStatus.RUNNING, readKvEntry(id)?.status)
            producing.markComplete(assertNotNull(delivered))
        } finally {
            pool.close()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `a newly scheduled job cannot run before its publication is confirmed`() = runBlocking {
        val real = natsPool.systemConnection().jetStream()
        val publishing = mockk<JetStream>(relaxed = true)
        lateinit var producing: NatsJobQueue
        every { publishing.subscribe(any<String>(), any<PullSubscribeOptions>()) } answers {
            real.subscribe(firstArg<String>(), secondArg<PullSubscribeOptions>())
        }
        every { publishing.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) } answers {
            val result = real.publish(firstArg<String>(), secondArg<ByteArray>(), thirdArg<PublishOptions>())
            assertNull(runBlocking { producing.dequeue(50.milliseconds) })
            result
        }
        val pool = poolWith(publishing)
        try {
            producing = NatsJobQueue("e2e-test", pool, json, lockFactory, processExpiredJobs = false)
            val id = producing.enqueueLater(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class), 500.milliseconds)

            assertEquals(JobStatus.PENDING, readKvEntry(id)?.status)
            val delivered = assertNotNull(producing.dequeue())
            assertEquals(id, delivered.id)
            producing.markComplete(delivered)
        } finally {
            pool.close()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `interrupted initial publication is recovered automatically`() = runBlocking {
        val real = natsPool.systemConnection().jetStream()
        val publishing = mockk<JetStream>(relaxed = true)
        every { publishing.subscribe(any<String>(), any<PullSubscribeOptions>()) } answers {
            real.subscribe(firstArg<String>(), secondArg<PullSubscribeOptions>())
        }
        every { publishing.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) } throws java.io.IOException("publish unavailable")
        val pool = poolWith(publishing)
        try {
            val failing = NatsJobQueue("e2e-test", pool, json, lockFactory, processExpiredJobs = false)
            val job = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
            job.setPersistentId(UUID.random())

            assertFailsWith<java.io.IOException> { failing.enqueueIfAbsent(job) }
            assertEquals(JobStatus.UNINITIALIZED, readKvEntry(job.id)?.status)
            queue.checkForExpiredJobs(Long.MAX_VALUE)

            assertEquals(JobStatus.PENDING, readKvEntry(job.id)?.status)
            queue.markComplete(assertNotNull(queue.dequeue()))
        } finally {
            pool.close()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `published delivery remains executable after confirmation fails`() = runBlocking {
        val connection = natsPool.systemConnection()
        val state = mockk<KeyValue>()
        every { state.keys() } answers { kv.keys() }
        every { state.get(any<String>()) } answers { kv.get(firstArg<String>()) }
        every { state.update(any<String>(), any<String>(), any<Long>()) } throws java.io.IOException("confirmation unavailable")
        val pool = poolWith(connection.jetStream(), state)
        try {
            val recovering = NatsJobQueue("e2e-test", pool, json, lockFactory, processExpiredJobs = false)
            val job = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
            val unpublished = job.serialize().copy(status = JobStatus.UNINITIALIZED)
            kv.put(job.id.toString(), json.encodeToString(SerializedJob.serializer(), unpublished))

            recovering.checkForExpiredJobs(Long.MAX_VALUE)

            assertEquals(JobStatus.UNINITIALIZED, readKvEntry(job.id)?.status)
            assertEquals(1L, connection.jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount)
            val delivered = assertNotNull(queue.dequeue(100.milliseconds))
            assertEquals(job.id, delivered.id)
            queue.markComplete(delivered)
        } finally {
            pool.close()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `confirmation retry does not duplicate an already published message`() = runBlocking {
        val connection = natsPool.systemConnection()
        val state = mockk<KeyValue>()
        every { state.create(any<String>(), any<ByteArray>()) } answers { kv.create(firstArg<String>(), secondArg<ByteArray>()) }
        every { state.update(any<String>(), any<String>(), any<Long>()) } throws java.io.IOException("confirmation unavailable")
        val pool = poolWith(connection.jetStream(), state)
        try {
            val failing = NatsJobQueue("e2e-test", pool, json, lockFactory, processExpiredJobs = false)
            val job = InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class)
            job.setPersistentId(UUID.random())
            assertFailsWith<java.io.IOException> { failing.enqueueIfAbsent(job) }
            assertEquals(JobStatus.UNINITIALIZED, readKvEntry(job.id)?.status)

            queue.checkForExpiredJobs(Long.MAX_VALUE)
            queue.checkForExpiredJobs(Long.MAX_VALUE)

            assertEquals(JobStatus.PENDING, readKvEntry(job.id)?.status)
            assertEquals(1L, connection.jetStreamManagement().getStreamInfo("jobs-e2e-test").streamState.msgCount)
            queue.markComplete(assertNotNull(queue.dequeue()))
        } finally {
            pool.close()
        }
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

    @OptIn(Internal::class)
    @Test
    fun `failed dequeue releases its real distributed lock`(): Unit = runBlocking {
        val id = queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), E2ETestJobExecutor::class))
        kv.put(id.toString(), "{invalid}")
        try {
            assertNull(queue.dequeue(100.milliseconds))
            val contender = lockFactory.create("job-$id")
            assertTrue(contender.tryAcquire(1000), "Failed deserialization must not leave a lock behind")
            contender.release()
        } finally {
            queue.markCancelled(id)
        }
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
