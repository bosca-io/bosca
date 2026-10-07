@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.sharedqueue.jobs.nats

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import bosca.db.ConnectionManager
import bosca.db.ConnectionManagerCallback
import bosca.db.asCoroutineContext
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.withContext
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
import io.nats.client.api.KeyValueConfiguration
import io.nats.client.api.KeyValueEntry
import io.nats.client.api.KeyValueOperation
import io.nats.client.api.KeyValueStatus
import io.nats.client.api.StreamInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Branch/edge coverage for [NatsJobQueue] beyond the happy paths in
 * NatsJobQueueTest: the lock-held vs. reacquire arms of markComplete/markFailed,
 * the terminal-vs-retry split, checkin, setDefinition, cancellation, lock
 * administration, and the dequeue drain loop's discard arms.
 */
class NatsJobQueueBranchTest {

    private val connection = mockk<Connection>(relaxed = true)
    private val nats = NatsConnectionPool(connection)
    private val json = Json
    private val js = mockk<JetStream>(relaxed = true)
    private val jsm = mockk<JetStreamManagement>(relaxed = true)
    private val kvm = mockk<KeyValueManagement>(relaxed = true)
    private val kv = mockk<KeyValue>(relaxed = true)
    private val scheduledKv = mockk<KeyValue>(relaxed = true)
    private val sub = mockk<JetStreamSubscription>(relaxed = true)
    private val lockFactory = mockk<DistributedLockFactory>()

    private lateinit var queue: NatsJobQueue

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }

        every { connection.jetStream() } returns js
        every { connection.jetStreamManagement() } returns jsm
        every { connection.keyValueManagement() } returns kvm
        every { connection.keyValue("job-state-test") } returns kv
        every { connection.keyValue("job-scheduled-test") } returns scheduledKv
        val keyValueStatus = mockk<KeyValueStatus> {
            every { limitMarkerTtl } returns java.time.Duration.ofMinutes(5)
        }
        every { kvm.getStatus(any()) } returns keyValueStatus
        every { jsm.getStreamInfo("jobs-test") } returns mockk<StreamInfo>()
        every { js.subscribe(any<String>(), any<PullSubscribeOptions>()) } returns sub

        coEvery { lockFactory.create(any()) } answers { newLock(held = true, acquires = true) }
        coEvery { lockFactory.forceRelease(any()) } returns true

        queue = NatsJobQueue("test", nats, json, lockFactory, processExpiredJobs = false)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun newLock(held: Boolean, acquires: Boolean) = mockk<DistributedLock>(relaxed = true) {
        every { isHeld } returns held
        coEvery { acquire(any(), any(), any()) } returns acquires
        coEvery { tryAcquire(any()) } returns acquires
        coEvery { renew(any()) } returns held
        coEvery { release() } returns true
    }

    /** A locked, id-bearing job that can be handed straight to markComplete/markFailed/checkin. */
    private fun lockedJob(
        status: JobStatus = JobStatus.RUNNING,
        maxFailures: Int = 10,
        child: Job? = null,
    ): Job {
        // Build via the module-internal constructor so maxFailures (a `val`) can be set.
        // A JsonElement `definition` argument disambiguates from the suspend `Job()` factory.
        val job = Job(
            definition = Json.parseToJsonElement("{}"),
            executor = NatsBranchExecutor::class,
            maxFailures = maxFailures,
        )
        job.lock = newLock(held = true, acquires = true)
        job.setId(UUID.random())
        job.setStatus(status)
        // runOnParentComplete = false: track the child for the fully-complete join
        // without attaching a RunChildOnCompleteListener callback (which would need a
        // job coroutine context to fire during markComplete).
        child?.let { job.addChild(it, runOnParentComplete = false) }
        return job
    }

    private fun serializedJob(id: UUID, status: JobStatus = JobStatus.RUNNING) = SerializedJob(
        id = id,
        parentId = null,
        type = "TestJob",
        status = status,
        failures = 0,
        maxFailures = 10,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        definition = Json.parseToJsonElement("{}"),
        executor = NatsBranchExecutor::class.qualifiedName!!,
        executorName = null,
        children = emptyList(),
        callbacks = emptyList(),
        context = Json.parseToJsonElement("{}"),
    )

    private fun entryFor(job: SerializedJob) = mockk<KeyValueEntry> {
        every { valueAsString } returns json.encodeToString(SerializedJob.serializer(), job)
        every { operation } returns KeyValueOperation.PUT
    }

    // ----- markComplete -----

    @Test
    fun `markComplete persists (not deletes) when children are still outstanding`() = runTest {
        // An incomplete child keeps the parent from being fully complete, so the
        // parent's state is written back rather than deleted.
        val child = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class).apply {
            lock = newLock(held = true, acquires = true)
            setId(UUID.random())
            setStatus(JobStatus.RUNNING)
        }
        val parent = lockedJob(status = JobStatus.RUNNING, child = child)

        queue.markComplete(parent)

        verify { kv.put(parent.id.toString(), any<String>()) }
        verify(exactly = 0) { kv.purge(parent.id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `markComplete reacquires the lock when the job is not locked`() = runTest {
        val id = UUID.random()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.lock = newLock(held = false, acquires = true)
        job.setId(id)
        // renew() returns held==false, so it must fall through to internalGetJob.
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id))

        queue.markComplete(job)

        // internalGetJob was used to fetch fresh, locked state.
        verify { kv.get(id.toString()) }
        verify { kv.purge(id.toString(), any<MessageTtl>()) }
    }

    // ----- markFailed -----

    @Test
    fun `markFailed with retry marks FAILED and naks with delay`() = runTest {
        val job = lockedJob(status = JobStatus.RUNNING)
        val msg = mockk<Message>(relaxed = true)
        job.message = msg

        queue.markFailed(job, RuntimeException("boom"), retry = true)

        assertTrue(job.status == JobStatus.FAILED)
        verify { kv.put(job.id.toString(), any<String>()) }
        verify { msg.nakWithDelay(any<java.time.Duration>()) }
        verify(exactly = 0) { kv.purge(job.id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `markFailed without retry is terminal and deletes state`() = runTest {
        val job = lockedJob(status = JobStatus.RUNNING)
        val msg = mockk<Message>(relaxed = true)
        job.message = msg

        queue.markFailed(job, RuntimeException("boom"), retry = false)

        assertTrue(job.status == JobStatus.FAILED_AND_COMPLETE)
        verify { kv.purge(job.id.toString(), any<MessageTtl>()) }
        verify { msg.ack() }
    }

    @Test
    fun `markFailed becomes terminal when retries are exhausted`() = runTest {
        // maxFailures = 1 → the first failure (failures becomes 1 >= 1) is terminal even with retry.
        val job = lockedJob(status = JobStatus.RUNNING, maxFailures = 1)

        queue.markFailed(job, RuntimeException("boom"), retry = true)

        assertTrue(job.status == JobStatus.FAILED_AND_COMPLETE)
        verify { kv.purge(job.id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `markFailed reacquires the lock when the job is not locked`() = runTest {
        val id = UUID.random()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.lock = newLock(held = false, acquires = true)
        job.setId(id)
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id))

        queue.markFailed(job, RuntimeException("boom"), retry = false)

        verify { kv.get(id.toString()) }
    }

    @Test
    fun `markComplete still releases the lock when the state write fails`() = runTest {
        val job = lockedJob(status = JobStatus.RUNNING)
        every { kv.purge(job.id.toString(), any<MessageTtl>()) } throws RuntimeException("kv write failed")
        val lock = job.lock!!
        assertFailsWith<RuntimeException> { queue.markComplete(job) }
        io.mockk.coVerify { lock.release() } // finally runs on the exception exit
    }

    @Test
    fun `markFailed still releases the lock when the state write fails`() = runTest {
        val job = lockedJob(status = JobStatus.RUNNING)
        every { kv.purge(job.id.toString(), any<MessageTtl>()) } throws RuntimeException("kv write failed")
        val lock = job.lock!!
        assertFailsWith<RuntimeException> { queue.markFailed(job, RuntimeException("boom"), retry = false) }
        io.mockk.coVerify { lock.release() }
    }

    // ----- checkin -----

    @Test
    fun `checkin renews and persists when locked`() = runTest {
        val job = lockedJob()
        val previousModified = OffsetDateTime.now().minusHours(1)
        job.modified = previousModified
        val persisted = slot<String>()
        every { kv.put(job.id.toString(), capture(persisted)) } returns 1L
        assertTrue(queue.checkin(job, 1000))
        val state = json.decodeFromString(SerializedJob.serializer(), persisted.captured)
        assertTrue(state.modified > previousModified, "Successful check-in must refresh stale-job liveness")
        assertEquals(job.modified, state.modified)
        every { kv.keys() } returns listOf(job.id.toString())
        every { kv.get(job.id.toString()) } returns entryFor(state)
        queue.checkForExpiredJobs(System.currentTimeMillis())
        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
    }

    @Test
    fun `checkin returns false when not locked`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.lock = newLock(held = false, acquires = false)
        job.setId(UUID.random())
        assertFalse(queue.checkin(job, 1000))
    }

    @Test
    fun `checkin returns false and does not persist when lock renewal fails`() = runTest {
        val job = lockedJob()
        val lock = job.lock!!
        coEvery { lock.renew(any()) } returns false
        val previousModified = job.modified

        assertFalse(queue.checkin(job, 1000))

        assertEquals(previousModified, job.modified)
        verify(exactly = 0) { kv.put(job.id.toString(), any<String>()) }
    }

    // ----- setDefinition -----

    @Test
    fun `setDefinition updates the payload and persists`() = runTest {
        val job = lockedJob()
        val newDef = Json.parseToJsonElement("""{"updated":true}""")

        queue.setDefinition(job, newDef)

        assertTrue(job.getDefinition() == newDef)
        verify { kv.put(job.id.toString(), any<String>()) }
    }

    // ----- markCancelled -----

    @Test
    fun `markCancelled purges both state and scheduled entries with a ttl`() = runTest {
        val id = UUID.random()
        queue.markCancelled(id)
        val stateTtl = slot<MessageTtl>()
        val scheduledTtl = slot<MessageTtl>()
        verify { kv.purge(id.toString(), capture(stateTtl)) }
        verify { scheduledKv.purge(id.toString(), capture(scheduledTtl)) }
        assertEquals("300s", stateTtl.captured.ttlString)
        assertEquals("300s", scheduledTtl.captured.ttlString)
    }

    @Test
    fun `markCancelled reports delete failures after attempting all cleanup`() = runTest {
        val id = UUID.random()
        every { kv.purge(id.toString(), any<MessageTtl>()) } throws RuntimeException("kv down")
        every { scheduledKv.purge(id.toString(), any<MessageTtl>()) } throws RuntimeException("scheduled down")

        val failure = assertFailsWith<RuntimeException> { queue.markCancelled(id) }

        assertEquals("kv down", failure.message)
        verify { kv.purge(id.toString(), any<MessageTtl>()) }
        verify { scheduledKv.purge(id.toString(), any<MessageTtl>()) }
    }

    // ----- lock administration -----

    @Test
    fun `clearJobLock force-releases the per-job lock`() = runTest {
        val id = UUID.random()
        queue.clearJobLock(id)
        coVerifyForceRelease("job-$id")
    }

    @Test
    fun `clearAllJobLocks force-releases every job's lock`() = runTest {
        val a = UUID.random()
        val b = UUID.random()
        every { kv.keys() } returns listOf(a.toString(), b.toString())
        queue.clearAllJobLocks()
        coVerifyForceRelease("job-$a")
        coVerifyForceRelease("job-$b")
    }

    @Test
    fun `clearAllJobLocks returns quietly when keys cannot be listed`() = runTest {
        every { kv.keys() } throws RuntimeException("kv down")
        queue.clearAllJobLocks()
    }

    private fun coVerifyForceRelease(key: String) = io.mockk.coVerify { lockFactory.forceRelease(key) }

    // ----- ensureInitialized create branches -----

    @Test
    fun `ensureInitialized creates the stream and kv stores when they are absent`() = runTest {
        // The queue built in setUp has not initialized yet; re-stub so the "not found → create"
        // arms run, then trigger lazy initialization through a queue call.
        every { jsm.getStreamInfo("jobs-test") } throws RuntimeException("stream absent")
        var stateCalls = 0
        var scheduledCalls = 0
        every { connection.keyValue("job-state-test") } answers {
            if (stateCalls++ == 0) throw RuntimeException("state absent") else kv
        }
        every { connection.keyValue("job-scheduled-test") } answers {
            if (scheduledCalls++ == 0) throw RuntimeException("scheduled absent") else scheduledKv
        }

        queue.markCancelled(UUID.random())

        verify { jsm.addStream(any()) }
        // Both KV stores had to be created after the initial lookup failed.
        val configurations = mutableListOf<KeyValueConfiguration>()
        verify(exactly = 2) { kvm.create(capture(configurations)) }
        assertTrue(configurations.all { it.limitMarkerTtl == java.time.Duration.ofMinutes(5) })
    }

    @Test
    fun `ensureInitialized enables limit markers on existing kv stores`() = runTest {
        val stateConfiguration = KeyValueConfiguration.builder().name("job-state-test").build()
        val scheduledConfiguration = KeyValueConfiguration.builder().name("job-scheduled-test").build()
        every { kvm.getStatus("job-state-test") } returns mockk {
            every { limitMarkerTtl } returns null
            every { configuration } returns stateConfiguration
        }
        every { kvm.getStatus("job-scheduled-test") } returns mockk {
            every { limitMarkerTtl } returns null
            every { configuration } returns scheduledConfiguration
        }

        queue.markCancelled(UUID.random())

        verify {
            kvm.update(match {
                it.bucketName == "job-state-test" && it.limitMarkerTtl == java.time.Duration.ofMinutes(5)
            })
            kvm.update(match {
                it.bucketName == "job-scheduled-test" && it.limitMarkerTtl == java.time.Duration.ofMinutes(5)
            })
        }
    }

    // ----- dequeue drain loop -----

    private fun stubFetch(vararg messages: Message) {
        // First fetch returns the messages, subsequent fetches drain to empty → dequeue returns null.
        every { sub.fetch(1, any<java.time.Duration>()) } returnsMany listOf(messages.toList(), emptyList<Message>())
    }

    private fun message(id: String): Message = mockk<Message>(relaxed = true) {
        every { data } returns id.toByteArray()
    }

    @Test
    fun `dequeue returns null when the queue is empty`() = runTest {
        every { sub.fetch(1, any<java.time.Duration>()) } returns emptyList()
        assertNull(queue.dequeue())
    }

    @Test
    fun `timed dequeue uses the caller wait timeout`() = runTest {
        every { sub.fetch(1, any<java.time.Duration>()) } returns emptyList()

        assertNull(queue.dequeue(125.milliseconds))

        verify { sub.fetch(1, java.time.Duration.ofMillis(125)) }
    }

    @Test
    fun `dequeue acks and skips a message with an unparseable id`() = runTest {
        val bad = message("not-a-uuid")
        stubFetch(bad)
        assertNull(queue.dequeue())
        verify { bad.ack() }
    }

    @Test
    fun `dequeue naks a message scheduled for the future`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns (System.currentTimeMillis() + 3_600_000).toString()
            every { operation } returns KeyValueOperation.PUT
        }
        assertNull(queue.dequeue())
        verify { msg.nakWithDelay(any<java.time.Duration>()) }
    }

    @Test
    fun `dequeue acks and skips a message whose state is gone`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns null
        every { kv.get(id.toString()) } returns null
        assertNull(queue.dequeue())
        verify { msg.ack() }
    }

    @Test
    fun `dequeue returns a running job on the happy path`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns null
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id, JobStatus.PENDING))

        val job = queue.dequeue()

        assertNotNull(job)
        assertTrue(job.status == JobStatus.RUNNING)
        assertNotNull(job.message)
    }

    @Test
    fun `dequeue returns null when the subscription is inactive`() = runTest {
        every { sub.fetch(1, any<java.time.Duration>()) } throws IllegalStateException("This subscription is inactive")
        assertNull(queue.dequeue())
    }

    @Test
    fun `dequeue returns null when fetch throws a generic error`() = runTest {
        every { sub.fetch(1, any<java.time.Duration>()) } throws RuntimeException("nats unavailable")
        assertNull(queue.dequeue())
    }

    @Test
    fun `dequeue returns null when the subscription throws a different IllegalState message`() = runTest {
        every { sub.fetch(1, any<java.time.Duration>()) } throws IllegalStateException("some other problem")
        assertNull(queue.dequeue())
    }

    @Test
    fun `dequeue proceeds past a purged scheduled entry`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.PURGE
            every { valueAsString } returns null
        }
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id, JobStatus.PENDING))
        assertNotNull(queue.dequeue())
    }

    @Test
    fun `dequeue acks a message whose state entry is purged`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns null
        every { kv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns "{}"
            every { operation } returns KeyValueOperation.PURGE
        }
        assertNull(queue.dequeue())
        verify { msg.ack() }
    }

    @Test
    fun `dequeue naks when the job lock cannot be acquired`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns null
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id, JobStatus.PENDING))
        // A lock that never acquires (wait=false path throws LockAcquisitionException).
        coEvery { lockFactory.create(any()) } returns newLock(held = false, acquires = false)

        assertNull(queue.dequeue())
        verify { msg.nakWithDelay(any<java.time.Duration>()) }
    }

    // ----- internalGetJob error arms (via getJob) -----

    @Test
    fun `getJob errors when the state is missing`() = runTest {
        val id = UUID.random()
        every { kv.get(id.toString()) } returns null
        assertFailsWith<IllegalStateException> { queue.getJob(id) { it } }
    }

    @Test
    fun `getJob errors when the stored value is empty`() = runTest {
        val id = UUID.random()
        every { kv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns null
            every { operation } returns KeyValueOperation.PUT
        }
        assertFailsWith<IllegalStateException> { queue.getJob(id) { it } }
    }

    @Test
    fun `getJob errors when the state read throws`() = runTest {
        val id = UUID.random()
        every { kv.get(id.toString()) } throws RuntimeException("kv read failed")
        // The read failure is logged and treated as a missing entry → error.
        assertFailsWith<IllegalStateException> { queue.getJob(id) { it } }
    }

    // ----- markFailed message-absent arms -----

    @Test
    fun `markFailed with retry and no message still persists FAILED`() = runTest {
        val job = lockedJob(status = JobStatus.RUNNING)
        queue.markFailed(job, RuntimeException("boom"), retry = true)
        assertTrue(job.status == JobStatus.FAILED)
        verify { kv.put(job.id.toString(), any<String>()) }
    }

    // ----- processExpiredJobs error arms -----

    @Test
    fun `checkForExpiredJobs returns quietly when keys cannot be listed`() = runTest {
        every { kv.keys() } throws RuntimeException("kv down")
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
    }

    @Test
    fun `checkForExpiredJobs skips entries that cannot be deserialized`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns "not-json"
            every { operation } returns KeyValueOperation.PUT
        }
        // The decode failure is caught per-entry; the scan continues and publishes nothing.
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
    }

    @Test
    fun `checkForExpiredJobs skips entries with no value`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns null
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
    }

    // ----- dbRunAfterCommit inside a transaction -----

    @Test
    fun `enqueue defers to commit when running inside a transaction`() = runTest {
        val cm = mockk<ConnectionManager>(relaxed = true)
        every { cm.inTransaction } returns true
        val callback = slot<ConnectionManagerCallback>()
        every { cm.addCallback(capture(callback)) } just Runs

        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        withContext(cm.asCoroutineContext()) { queue.enqueue(job) }

        // Deferred: nothing is published until the transaction commits.
        verify(exactly = 0) { kv.put(job.id.toString(), any<String>()) }
        callback.captured.onCommit()
        callback.captured.onRelease()
        verify { kv.put(job.id.toString(), any<String>()) }
    }

    @Test
    fun `enqueue runs immediately when the connection has no active transaction`() = runTest {
        val cm = mockk<ConnectionManager>(relaxed = true)
        every { cm.inTransaction } returns false
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        withContext(cm.asCoroutineContext()) { queue.enqueue(job) }
        // No transaction → the enqueue runs right away rather than deferring to commit.
        verify { kv.put(job.id.toString(), any<String>()) }
    }

    // ----- verifyLocked: lock present but not held -----

    @Test
    fun `setJob throws when the lock is present but not held`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.lock = newLock(held = false, acquires = false)
        job.setId(UUID.random())
        assertFailsWith<IllegalStateException> { queue.setJob(job) }
    }

    // ----- markComplete/markFailed lock reacquisition variants -----

    /** isHeld reports false to isLockHeld, then true so the later isLocked checks pass. */
    private fun renewableLock() = mockk<DistributedLock>(relaxed = true) {
        every { isHeld } returnsMany listOf(false, true, true, true, true, true)
        coEvery { renew(any()) } returns true
        coEvery { release() } returns true
    }

    @Test
    fun `markComplete renews in place without reacquiring`() = runTest {
        val id = UUID.random()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.lock = renewableLock()
        job.setId(id)
        queue.markComplete(job)
        verify { kv.purge(id.toString(), any<MessageTtl>()) }
        verify(exactly = 0) { kv.get(id.toString()) } // no reacquire
    }

    @Test
    fun `markComplete reacquires when the job has no lock at all`() = runTest {
        val id = UUID.random()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.setId(id) // lock stays null → the ?: false arm of the renew check
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id))
        queue.markComplete(job)
        verify { kv.get(id.toString()) }
        verify { kv.purge(id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `markFailed renews in place without reacquiring`() = runTest {
        val id = UUID.random()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.lock = renewableLock()
        job.setId(id)
        queue.markFailed(job, RuntimeException("boom"), retry = false)
        verify(exactly = 0) { kv.get(id.toString()) }
        verify { kv.purge(id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `markFailed reacquires when the job has no lock at all`() = runTest {
        val id = UUID.random()
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.setId(id) // lock null → the `?: false` arm of the renew check
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id))
        queue.markFailed(job, RuntimeException("boom"), retry = false)
        verify { kv.get(id.toString()) }
        verify { kv.purge(id.toString(), any<MessageTtl>()) }
    }

    // ----- enqueueLater non-positive timeout delegates to enqueue -----

    @Test
    fun `enqueueLater with a non-positive timeout enqueues immediately`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        queue.enqueueLater(job, kotlin.time.Duration.ZERO)
        // No scheduled entry is written for an immediate enqueue.
        verify(exactly = 0) { scheduledKv.put(any<String>(), any<String>()) }
        verify { kv.put(job.id.toString(), any<String>()) }
    }

    // ----- dequeue scheduled/entry edge arms -----

    @Test
    fun `dequeue proceeds past a scheduled entry that is deleted`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.DELETE
            every { valueAsString } returns null
        }
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id, JobStatus.PENDING))
        assertNotNull(queue.dequeue())
    }

    @Test
    fun `dequeue proceeds when a scheduled entry has no value`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.PUT
            every { valueAsString } returns null
        }
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id, JobStatus.PENDING))
        assertNotNull(queue.dequeue())
        // A present-but-valueless scheduled entry is cleared before proceeding.
        verify { scheduledKv.purge(id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `dequeue proceeds for a past-due scheduled entry`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.PUT
            every { valueAsString } returns (System.currentTimeMillis() - 60_000).toString()
        }
        every { kv.get(id.toString()) } returns entryFor(serializedJob(id, JobStatus.PENDING))
        assertNotNull(queue.dequeue())
        verify { scheduledKv.purge(id.toString(), any<MessageTtl>()) }
    }

    @Test
    fun `dequeue acks a message whose state entry has no value`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns null
        every { kv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns null
            every { operation } returns KeyValueOperation.PUT
        }
        assertNull(queue.dequeue())
        verify { msg.ack() }
    }

    @Test
    fun `dequeue acks a message whose state entry is a tombstone`() = runTest {
        val id = UUID.random()
        val msg = message(id.toString())
        stubFetch(msg)
        every { scheduledKv.get(id.toString()) } returns null
        every { kv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns "{}"
            every { operation } returns KeyValueOperation.DELETE
        }
        assertNull(queue.dequeue())
        verify { msg.ack() }
    }

    // ----- lock guards & enqueue guard -----

    @Test
    fun `setJob throws when the job has no lock`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.setId(UUID.random()) // no lock attached → verifyLocked fails
        assertFailsWith<IllegalStateException> { queue.setJob(job) }
    }

    @Test
    fun `checkin returns false when the job has no lock at all`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.setId(UUID.random())
        assertFalse(queue.checkin(job, 1000))
    }

    @Test
    fun `enqueue rejects a job that still carries a delivered message`() = runTest {
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        job.message = mockk<Message>(relaxed = true)
        assertFailsWith<IllegalStateException> { queue.enqueue(job) }
    }

    private fun stalePending(id: UUID): SerializedJob {
        val past = OffsetDateTime.now().minusHours(1)
        return SerializedJob(
            id = id, parentId = null, type = "TestJob", status = JobStatus.PENDING, failures = 0, maxFailures = 10,
            created = past, modified = past, definition = Json.parseToJsonElement("{}"),
            executor = NatsBranchExecutor::class.qualifiedName!!, executorName = null,
            children = emptyList(), callbacks = emptyList(), context = Json.parseToJsonElement("{}"),
        )
    }

    @Test
    fun `stale pending with a tombstoned scheduled entry is re-published`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns entryFor(stalePending(id))
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.DELETE
            every { valueAsString } returns null
        }
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify { js.publish("jobs.test", id.toString().toByteArray(), any<PublishOptions>()) }
    }

    @Test
    fun `stale pending with a purged scheduled entry is re-published`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns entryFor(stalePending(id))
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.PURGE
            every { valueAsString } returns null
        }
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify { js.publish("jobs.test", id.toString().toByteArray(), any<PublishOptions>()) }
    }

    @Test
    fun `stale pending is re-published when the scheduled lookup throws`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns entryFor(stalePending(id))
        every { scheduledKv.get(id.toString()) } throws RuntimeException("kv down")
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify { js.publish("jobs.test", id.toString().toByteArray(), any<PublishOptions>()) }
    }

    @Test
    fun `stale pending with a valueless scheduled entry is re-published`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns entryFor(stalePending(id))
        every { scheduledKv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { operation } returns KeyValueOperation.PUT
            every { valueAsString } returns null
        }
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify { js.publish("jobs.test", id.toString().toByteArray(), any<PublishOptions>()) }
    }

    @Test
    fun `checkForExpiredJobs skips an entry whose value is null`() = runTest {
        val id = UUID.random()
        every { kv.keys() } returns listOf(id.toString())
        every { kv.get(id.toString()) } returns mockk<KeyValueEntry> {
            every { valueAsString } returns null
            every { operation } returns KeyValueOperation.PUT
        }
        queue.checkForExpiredJobs(Long.MAX_VALUE)
        verify(exactly = 0) { js.publish(any<String>(), any<ByteArray>(), any<PublishOptions>()) }
    }

    // ----- defensive guards fire when the NATS client misbehaves -----

    /** Force an internal field to a value, simulating a client that returned an unexpected null. */
    private fun setInternal(name: String, value: Any?) {
        val field = NatsJobQueue::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(queue, value)
    }

    @Test
    fun `kv accessor fails clearly when the KeyValue store is missing`() = runTest {
        // A live subscription short-circuits ensureInitialized, but a null _kv trips the guard.
        setInternal("_sub", mockk<JetStreamSubscription>(relaxed = true))
        setInternal("_kv", null)
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class).apply {
            lock = newLock(held = true, acquires = true)
            setId(UUID.random())
        }
        val ex = assertFailsWith<IllegalStateException> { queue.setJob(job) }
        assertEquals("KeyValue not initialized", ex.message)
    }

    @Test
    fun `js accessor fails clearly when JetStream is missing`() = runTest {
        setInternal("_sub", mockk<JetStreamSubscription>(relaxed = true))
        setInternal("_kv", kv)
        setInternal("_js", null)
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        val ex = assertFailsWith<IllegalStateException> { queue.enqueue(job) }
        assertEquals("JetStream not initialized", ex.message)
    }

    @Test
    fun `scheduledKv accessor fails clearly when the scheduled store is missing`() = runTest {
        setInternal("_sub", mockk<JetStreamSubscription>(relaxed = true))
        setInternal("_js", js)
        setInternal("_kv", kv)
        setInternal("_scheduledKv", null)
        val job = InternalJobConstructor(Json.parseToJsonElement("{}"), NatsBranchExecutor::class)
        val ex = assertFailsWith<IllegalStateException> { queue.enqueueLater(job, kotlin.time.Duration.parse("10s")) }
        assertEquals("Scheduled KeyValue not initialized", ex.message)
    }

    @Test
    fun `dequeue returns null when the fetch yields a null message`() = runTest {
        // A non-empty batch whose element is null exercises the `firstOrNull() ?: return null` guard.
        every { sub.fetch(1, any<java.time.Duration>()) } returns java.util.Collections.singletonList<Message>(null)
        assertNull(queue.dequeue())
    }

    // ----- callback notification loop -----

    @Test
    fun `markComplete notifies the job's status listeners`() = runTest {
        val pubsub = mockk<bosca.pubsub.PubSubService>(relaxed = true)
        provides<bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener> {
            bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener(pubsub)
        }
        val job = lockedJob(status = JobStatus.RUNNING)
        job.addCallback(bosca.sharedqueue.jobs.JobCallback(listener = bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener::class))

        queue.markComplete(job)

        io.mockk.coVerify { pubsub.publish(any(), any(), any<bosca.sharedqueue.jobs.listeners.JobStatusNotification>()) }
    }

    @Test
    fun `markFailed notifies the job's status listeners`() = runTest {
        val pubsub = mockk<bosca.pubsub.PubSubService>(relaxed = true)
        provides<bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener> {
            bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener(pubsub)
        }
        val job = lockedJob(status = JobStatus.RUNNING)
        job.addCallback(bosca.sharedqueue.jobs.JobCallback(listener = bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener::class))

        queue.markFailed(job, RuntimeException("boom"), retry = false)

        io.mockk.coVerify { pubsub.publish(any(), any(), any<bosca.sharedqueue.jobs.listeners.JobStatusNotification>()) }
    }
}

private class NatsBranchExecutor : JobExecutor {
    override suspend fun execute() {}
}
