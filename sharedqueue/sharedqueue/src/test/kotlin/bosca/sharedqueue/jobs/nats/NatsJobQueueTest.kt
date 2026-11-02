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
    fun `enqueueIfAbsent leaves existing durable state and delivery unchanged`() = runTest {
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
        verify(exactly = 0) {
            js.publish("jobs.test", jobId.toString().toByteArray(), any<PublishOptions>())
        }
        verify(exactly = 0) { kv.update(jobId.toString(), any<String>(), any()) }
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
        every { kv.put(any<String>(), any<String>()) } returns 77L
        every { js.publish(any<String>(), any<ByteArray>(), capture(options)) } returns mockk(relaxed = true)
        val jobId = queue.enqueueLater(job, delay)
        assertNotNull(jobId)
        assertEquals("$jobId-77", options.captured.messageId)
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
            msg.nakWithDelay(java.time.Duration.ofSeconds(30))
            kv.put(reEnqueuedId.toString(), any<String>())
            scheduledKv.put(reEnqueuedId.toString(), any<String>())
        }
        verify(exactly = 0) { msg.ack() }
        verify(exactly = 0) { js.publish("jobs.test", any<ByteArray>(), any<PublishOptions>()) }
    }

    private fun storedJob(id: UUID, status: JobStatus) = SerializedJob(
        id = id, parentId = null, type = "TestJob", status = status, failures = 0, maxFailures = 10,
        created = bosca.serialization.OffsetDateTime.now().minusHours(1),
        modified = bosca.serialization.OffsetDateTime.now().minusHours(1),
        definition = Json.parseToJsonElement("{}"), executor = TestJobExecutor::class.qualifiedName.orEmpty(),
        executorName = null, children = emptyList(), callbacks = emptyList(), context = Json.parseToJsonElement("{}"),
    )

    private fun entryFor(job: SerializedJob) = mockk<KeyValueEntry> {
        every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), job)
        every { operation } returns KeyValueOperation.PUT
        every { revision } returns 7L
    }

    @Test
    fun `automatic recovery leaves all published execution states alone regardless of age`() = runTest {
        val jobs = listOf(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.FAILED, JobStatus.COMPLETE, JobStatus.FAILED_AND_COMPLETE)
            .map { storedJob(UUID.random(), it) }
        every { kv.keys() } returns jobs.map { it.id.toString() }
        jobs.forEach { job -> every { kv.get(job.id.toString()) } returns entryFor(job) }

        queue.checkForExpiredJobs(Long.MAX_VALUE)

        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
        verify(exactly = 0) { kv.put(any<String>(), any<String>()) }
    }

    @Test
    fun `automatic recovery retries unconfirmed publication with a stable revision key`() = runTest {
        val job = storedJob(UUID.random(), JobStatus.UNINITIALIZED)
        val future = storedJob(UUID.random(), JobStatus.UNINITIALIZED).copy(modified = bosca.serialization.OffsetDateTime.now().plusHours(1))
        every { kv.keys() } returns listOf(job.id.toString(), future.id.toString())
        every { kv.get(job.id.toString()) } returns entryFor(job)
        every { kv.get(future.id.toString()) } returns entryFor(future)
        val options = mutableListOf<PublishOptions>()
        every { js.publish(any<String>(), any<ByteArray>(), capture(options)) } returns mockk(relaxed = true)

        repeat(2) { queue.checkForExpiredJobs(System.currentTimeMillis()) }

        assertEquals(listOf("${job.id}-7", "${job.id}-7"), options.map { it.messageId })
        verify(exactly = 0) { js.publish("jobs.test", future.id.toString().toByteArray(), any<PublishOptions>()) }
    }

    @Test
    fun `manual redrive repairs published pending and running jobs`() = runTest {
        val jobs = listOf(JobStatus.PENDING, JobStatus.RUNNING).map { storedJob(UUID.random(), it) }
        every { kv.keys() } returns jobs.map { it.id.toString() }
        jobs.forEach { job -> every { kv.get(job.id.toString()) } returns entryFor(job) }
        every { scheduledKv.get(any()) } returns null

        queue.expireAllJobs()

        jobs.forEach { job -> verify { js.publish("jobs.test", job.id.toString().toByteArray(), any<PublishOptions>()) } }
    }

    @Test
    fun `manual redrive preserves future scheduled work`() = runTest {
        val job = storedJob(UUID.random(), JobStatus.PENDING)
        every { kv.keys() } returns listOf(job.id.toString())
        every { kv.get(job.id.toString()) } returns entryFor(job)
        every { scheduledKv.get(job.id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.PUT
            every { valueAsString } returns (System.currentTimeMillis() + 3_600_000).toString()
        }

        queue.expireAllJobs()

        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
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
