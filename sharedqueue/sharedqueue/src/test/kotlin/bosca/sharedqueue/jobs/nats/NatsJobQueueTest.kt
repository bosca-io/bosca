package bosca.sharedqueue.jobs.nats

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.core.annotations.Internal
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.JetStreamManagement
import io.nats.client.JetStreamSubscription
import io.nats.client.KeyValue
import io.nats.client.KeyValueManagement
import io.nats.client.Message
import io.nats.client.MessageTtl
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import io.nats.client.api.KeyValueStatus
import io.nats.client.api.StreamInfo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class NatsJobQueueTest {

    private val connection = mockk<Connection>(relaxed = true)
    private val nats = NatsConnectionPool(connection)
    private val json = Json
    private val js = mockk<JetStream>(relaxed = true)
    private val jsm = mockk<JetStreamManagement>(relaxed = true)
    private val kvm = mockk<KeyValueManagement>(relaxed = true)
    private val kv = mockk<KeyValue>(relaxed = true)
    private val scheduledKv = mockk<KeyValue>(relaxed = true)
    private val sub = mockk<JetStreamSubscription>(relaxed = true)

    private lateinit var queue: NatsJobQueue

    @OptIn(InternalDI::class)
    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @BeforeTest
    fun setup() {
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }
        every { connection.jetStream() } returns js
        every { connection.jetStreamManagement() } returns jsm
        every { connection.keyValueManagement() } returns kvm

        // Mock KV creation
        every { connection.keyValue("job-state-test") } returns kv
        every { connection.keyValue("job-scheduled-test") } returns scheduledKv
        val keyValueStatus = mockk<KeyValueStatus> {
            every { limitMarkerTtl } returns java.time.Duration.ofMinutes(5)
        }
        every { kvm.getStatus(any()) } returns keyValueStatus

        // Mock stream check
        every { jsm.getStreamInfo("jobs-test") } returns mockk<StreamInfo>()

        // Mock subscription creation before queue init
        every { js.subscribe(any<String>(), any<PullSubscribeOptions>()) } returns sub

        val lockFactory = mockk<DistributedLockFactory>()
        coEvery { lockFactory.create(any()) } answers {
            mockk<DistributedLock>(relaxed = true) {
                every { isHeld } returns true
                coEvery { acquire(any(), any(), any()) } returns true
                coEvery { tryAcquire(any()) } returns true
            }
        }
        // Disable the init-block background sweep loop. It runs on the real JobsDispatcher
        // thread pool and would invoke the same mocks these tests stub/verify, racing MockK's
        // (non-thread-safe) call recorder and intermittently throwing MockKException. Every
        // test drives checkForExpiredJobs/enqueue/dequeue directly, so the loop isn't needed.
        queue = NatsJobQueue("test", nats, json, lockFactory, processExpiredJobs = false)
    }

    @OptIn(Internal::class)
    @Test
    fun testEnqueue() = runTest {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class
        )
        val jobId = queue.enqueue(job)
        assertNotNull(jobId)
        verify {
            kv.put(jobId.toString(), any<String>())
            js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>())
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent creates durable state and publishes a delivery`() = runTest {
        val jobId = UUID.random()
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("""{"attempt":1}"""),
            executor = TestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }

        assertEquals(jobId, queue.enqueueIfAbsent(job))

        verify(exactly = 1) {
            kv.create(jobId.toString(), any<ByteArray>())
            js.publish("jobs.test", jobId.toString().toByteArray(), any<PublishOptions>())
        }
        verify(exactly = 0) { kv.put(jobId.toString(), any<String>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent leaves existing durable state unchanged and republishes delivery`() = runTest {
        val jobId = UUID.random()
        val existing = mockk<KeyValueEntry>()
        every { kv.create(jobId.toString(), any<ByteArray>()) } throws
            IllegalStateException("key already exists")
        every { kv.get(jobId.toString()) } returns existing
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("""{"attempt":2}"""),
            executor = TestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }

        assertEquals(jobId, queue.enqueueIfAbsent(job))

        verify(exactly = 1) { kv.get(jobId.toString()) }
        verify(exactly = 0) { kv.put(jobId.toString(), any<String>()) }
        verify(exactly = 1) {
            js.publish("jobs.test", jobId.toString().toByteArray(), any<PublishOptions>())
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent preserves cancellation while checking an existing key`() = runTest {
        val jobId = UUID.random()
        every { kv.create(jobId.toString(), any<ByteArray>()) } throws
            IllegalStateException("create failed")
        every { kv.get(jobId.toString()) } throws CancellationException("cancelled")
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }

        assertFailsWith<CancellationException> {
            queue.enqueueIfAbsent(job)
        }
        verify(exactly = 0) {
            js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>())
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent retains both create and lookup failures`() = runTest {
        val jobId = UUID.random()
        val createFailure = IllegalStateException("create failed")
        val readFailure = IllegalArgumentException("lookup failed")
        every { kv.create(jobId.toString(), any<ByteArray>()) } throws createFailure
        every { kv.get(jobId.toString()) } throws readFailure
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }

        val failure = assertFailsWith<IllegalStateException> {
            queue.enqueueIfAbsent(job)
        }
        assertEquals(createFailure.message, failure.message)
        verify(exactly = 1) { kv.get(jobId.toString()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `enqueueIfAbsent rethrows create failure when no durable key exists`() = runTest {
        val jobId = UUID.random()
        val createFailure = IllegalStateException("create failed")
        every { kv.create(jobId.toString(), any<ByteArray>()) } throws createFailure
        every { kv.get(jobId.toString()) } returns null
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class,
        ).apply {
            setPersistentId(jobId)
        }

        val failure = assertFailsWith<IllegalStateException> {
            queue.enqueueIfAbsent(job)
        }
        assertEquals(createFailure.message, failure.message)
    }

    @OptIn(Internal::class)
    @Test
    fun testEnqueueLater() = runTest {
        val job = InternalJobConstructor(
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class
        )
        val delay = Duration.parse("10s")
        val options = slot<PublishOptions>()
        every { js.publish(any<String>(), any<ByteArray>(), capture(options)) } returns mockk(relaxed = true)
        val jobId = queue.enqueueLater(job, delay)
        assertNotNull(jobId)
        assertTrue(options.captured.messageId.startsWith("$jobId-scheduled-"))
        verify {
            kv.put(jobId.toString(), any<String>())
            scheduledKv.put(jobId.toString(), any<String>())
            js.publish("jobs.test", any<ByteArray>(), options.captured)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun testEnqueueLaterWithMessage() = runTest {
        val jobId = UUID.random()
        val msg = mockk<Message>(relaxed = true)

        every { sub.fetch(1, any<java.time.Duration>()) } returns listOf(msg)
        every { msg.data } returns jobId.toString().toByteArray()

        val serializedJob = SerializedJob(
            id = jobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.RUNNING,
            failures = 0,
            maxFailures = 10,
            created = bosca.serialization.OffsetDateTime.now(),
            modified = bosca.serialization.OffsetDateTime.now(),
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )
        every { kv.get(jobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), serializedJob)
            every { operation } returns KeyValueOperation.PUT
        }
        every { scheduledKv.get(any()) } returns null

        val job = queue.dequeue()
        assertNotNull(job)
        assertNotNull(job.message)

        val delay = Duration.parse("30s")
        val reEnqueuedId = queue.enqueueLater(job, delay)
        assertNotNull(reEnqueuedId)
        assertNull(job.message)

        verify {
            msg.ack()
            kv.put(reEnqueuedId.toString(), any<String>())
            scheduledKv.put(reEnqueuedId.toString(), any<String>())
            js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>())
        }
    }

    @Test
    fun testCheckForExpiredJobs() = runTest {
        val expiredJobId = UUID.random()
        val recentJobId = UUID.random()

        val pastTime = bosca.serialization.OffsetDateTime.now().minusHours(1)
        val futureTime = bosca.serialization.OffsetDateTime.now()

        val expiredJob = SerializedJob(
            id = expiredJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.RUNNING,
            failures = 0,
            maxFailures = 10,
            created = pastTime,
            modified = pastTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        val recentJob = SerializedJob(
            id = recentJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.RUNNING,
            failures = 0,
            maxFailures = 10,
            created = futureTime,
            modified = futureTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        every { kv.keys() } returns listOf(expiredJobId.toString(), recentJobId.toString())
        every { kv.get(expiredJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), expiredJob)
            every { operation } returns KeyValueOperation.PUT
        }
        every { kv.get(recentJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), recentJob)
            every { operation } returns KeyValueOperation.PUT
        }

        // Use a time that is after the expired job's modified + 1800000ms threshold (in milliseconds)
        val checkTime = pastTime.toEpochSecond() * 1000 + 1800001

        queue.checkForExpiredJobs(checkTime)

        // Expired job should be re-enqueued
        verify {
            js.publish("jobs.test", expiredJobId.toString().toByteArray(), any<PublishOptions>())
        }

        // Recent job should NOT be re-enqueued
        verify(exactly = 0) {
            js.publish("jobs.test", recentJobId.toString().toByteArray(), any<PublishOptions>())
        }
    }

    @Test
    fun testCheckForExpiredJobsRePublishesExpiredPending() = runTest {
        val pendingJobId = UUID.random()

        val pastTime = bosca.serialization.OffsetDateTime.now().minusHours(1)

        val pendingJob = SerializedJob(
            id = pendingJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = pastTime,
            modified = pastTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        every { kv.keys() } returns listOf(pendingJobId.toString())
        every { kv.get(pendingJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), pendingJob)
            every { operation } returns KeyValueOperation.PUT
        }

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        // Stale PENDING job should be re-published
        verify {
            js.publish("jobs.test", pendingJobId.toString().toByteArray(), any<PublishOptions>())
        }
    }

    @Test
    fun testCheckForExpiredJobsSkipsRecentPending() = runTest {
        val pendingJobId = UUID.random()

        val recentTime = bosca.serialization.OffsetDateTime.now()

        val pendingJob = SerializedJob(
            id = pendingJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = recentTime,
            modified = recentTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        val entry = mockk<KeyValueEntry>()
        every { entry.valueAsString } returns json.encodeToString(SerializedJob.serializer(), pendingJob)
        every { entry.operation } returns KeyValueOperation.PUT
        every { kv.keys() } returns listOf(pendingJobId.toString())
        every { kv.get(pendingJobId.toString()) } returns entry

        // Use a time that is before the 5-minute threshold
        val checkTime = recentTime.toEpochSecond() * 1000 + 60000 // only 1 minute

        queue.checkForExpiredJobs(checkTime)

        // Recent PENDING job should NOT be re-published
        verify(exactly = 0) {
            js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>())
        }
    }

    @Test
    fun testCheckForExpiredJobsSkipsScheduledFuturePending() = runTest {
        val pendingJobId = UUID.random()

        // Modified an hour ago, so it is well past the 5-minute stale threshold.
        val pastTime = bosca.serialization.OffsetDateTime.now().minusHours(1)

        val pendingJob = SerializedJob(
            id = pendingJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = pastTime,
            modified = pastTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        every { kv.keys() } returns listOf(pendingJobId.toString())
        every { kv.get(pendingJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), pendingJob)
            every { operation } returns KeyValueOperation.PUT
        }
        // Scheduled an hour into the future. Even though the job is stale by age, it is
        // intentionally delayed (enqueueLater): its message is already in the stream and
        // gets NAK'd-until-due, so the sweep must NOT re-publish it (that was the flood bug).
        val scheduledAt = System.currentTimeMillis() + 3_600_000
        every { scheduledKv.get(pendingJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns scheduledAt.toString()
            every { operation } returns KeyValueOperation.PUT
        }

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        verify(exactly = 0) {
            js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>())
        }
    }

    @Test
    fun testCheckForExpiredJobsRePublishesPastDueScheduledPending() = runTest {
        val pendingJobId = UUID.random()

        val pastTime = bosca.serialization.OffsetDateTime.now().minusHours(1)

        val pendingJob = SerializedJob(
            id = pendingJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = pastTime,
            modified = pastTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        every { kv.keys() } returns listOf(pendingJobId.toString())
        every { kv.get(pendingJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), pendingJob)
            every { operation } returns KeyValueOperation.PUT
        }
        // Scheduled time already passed: the job is genuinely due, and its message may have
        // been lost, so the stale-PENDING safety net must still re-publish it (no zombies).
        val scheduledAt = System.currentTimeMillis() - 3_600_000
        every { scheduledKv.get(pendingJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns scheduledAt.toString()
            every { operation } returns KeyValueOperation.PUT
        }

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        verify {
            js.publish("jobs.test", pendingJobId.toString().toByteArray(), any<PublishOptions>())
        }
    }

    @Test
    fun testCheckForExpiredJobsUsesStableRetryMessageId() = runTest {
        val pendingJobId = UUID.random()

        val pastTime = bosca.serialization.OffsetDateTime.now().minusHours(1)

        val pendingJob = SerializedJob(
            id = pendingJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = pastTime,
            modified = pastTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        every { kv.keys() } returns listOf(pendingJobId.toString())
        every { kv.get(pendingJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), pendingJob)
            every { operation } returns KeyValueOperation.PUT
        }
        // Not scheduled, so it falls through to the stale-PENDING re-publish path.
        every { scheduledKv.get(pendingJobId.toString()) } returns null

        val optsSlot = slot<PublishOptions>()
        every { js.publish(any<String>(), any<ByteArray>(), capture(optsSlot)) } returns mockk(relaxed = true)

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        // The re-queue id must be stable per job (no nanoTime suffix) so the dedup window
        // collapses repeated sweeps, and namespaced with "-retry" so it never collides
        // with enqueue()'s messageId(jobId).
        assertEquals("$pendingJobId-retry", optsSlot.captured.messageId)
    }

    @Test
    fun testCheckForExpiredJobsSkipsComplete() = runTest {
        val completeJobId = UUID.random()

        val pastTime = bosca.serialization.OffsetDateTime.now().minusHours(1)

        val completeJob = SerializedJob(
            id = completeJobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.COMPLETE,
            failures = 0,
            maxFailures = 10,
            created = pastTime,
            modified = pastTime,
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )

        every { kv.keys() } returns listOf(completeJobId.toString())
        every { kv.get(completeJobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), completeJob)
            every { operation } returns KeyValueOperation.PUT
        }

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        // COMPLETE job should not be re-published
        verify(exactly = 0) {
            js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>())
        }
    }

    @Test
    fun testMarkComplete() = runTest {
        val jobId = UUID.random()
        val msg = mockk<Message>(relaxed = true)

        every { sub.fetch(1, any<java.time.Duration>()) } returns listOf(msg)
        every { msg.data } returns jobId.toString().toByteArray()

        val serializedJob = SerializedJob(
            id = jobId,
            parentId = null,
            type = "TestJob",
            status = JobStatus.PENDING,
            failures = 0,
            maxFailures = 10,
            created = bosca.serialization.OffsetDateTime.now(),
            modified = bosca.serialization.OffsetDateTime.now(),
            definition = Json.parseToJsonElement("{}"),
            executor = TestJobExecutor::class.qualifiedName!!,
            executorName = null,
            children = emptyList(),
            callbacks = emptyList(),
            context = Json.parseToJsonElement("{}")
        )
        every { kv.get(jobId.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), serializedJob)
            every { operation } returns KeyValueOperation.PUT
        }
        every { scheduledKv.get(any()) } returns null

        val job = queue.dequeue()
        assertNotNull(job)

        queue.markComplete(job)

        verify {
            msg.ack()
            kv.purge(jobId.toString(), any<MessageTtl>())
        }
    }
}

class TestJobExecutor : JobExecutor {
    override suspend fun execute() {}
}
