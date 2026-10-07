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
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import io.mockk.mockk
import io.nats.client.KeyValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the **consume → execute → close** path against a real
 * NATS JetStream, which the existing [NatsJobQueueEndToEndTest] does not exercise:
 * those tests drive the queue one operation at a time on a single thread, so they
 * cannot catch a regression where enqueued jobs are never actually delivered to a
 * worker and sit `PENDING` forever (the production symptom).
 *
 * Every queue here is built with `processExpiredJobs = false` so the 30-second
 * stale-job sweep cannot silently re-publish a message and mask a genuine delivery
 * failure — if the consume path drops or stalls a job, these tests time out and fail.
 */
class NatsJobRunnerPipelineTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var lockFactory: NatsDistributedLockFactory
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

        // The execute()/markComplete() path wraps work in withRequestCache +
        // withConnectionManager; relaxed mocks let those no-op without real infra.
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    private fun kvFor(queueName: String): KeyValue =
        runBlocking { natsPool.systemConnection() }.keyValue("job-state-$queueName")

    private fun kvDrained(kv: KeyValue, ids: Set<UUID>): Boolean =
        ids.all { kv.get(it.toString())?.valueAsString == null }

    @OptIn(Internal::class)
    @Test
    fun `every enqueued job is delivered to a concurrent consumer`() = runBlocking {
        // Reproduces the production shape: many jobs published in a burst, then
        // multiple fetchers pulling concurrently from the one shared subscription.
        val queueName = "pipeline-consume-${UUID.random().toString().substring(0, 8)}"
        val queue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = false)

        val count = 40
        val ids = (1..count).map { i ->
            queue.enqueue(
                InternalJobConstructor(
                    definition = Json.parseToJsonElement("""{"i":$i}"""),
                    executor = PipelineNoopExecutor::class,
                )
            )
        }.toSet()
        val kv = kvFor(queueName)

        val consumed = ConcurrentHashMap.newKeySet<UUID>()
        val fetcherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repeat(8) {
            fetcherScope.launch {
                while (isActive && consumed.size < count) {
                    val job = queue.dequeue() ?: continue
                    consumed.add(job.getId())
                    queue.markComplete(job)
                }
            }
        }

        try {
            withTimeout(60_000) {
                while (consumed.size < count) delay(50)
            }
        } finally {
            // Cancel without joining: a fetcher blocked in fetch(1, 30s) finishes in
            // the background and is harmless; we don't want the 30s tail in the test.
            fetcherScope.cancel()
        }

        assertEquals(count, consumed.size, "every enqueued job must be consumed; missing=${ids - consumed}")
        assertTrue(kvDrained(kv, ids), "all job state must be removed once consumed and completed")
    }

    @OptIn(Internal::class)
    @Test
    fun `JobRunner runs every enqueued job to completion`() = runBlocking {
        val executions = AtomicInteger(0)
        provides<PipelineCountingExecutor> { PipelineCountingExecutor(executions) }

        val queueName = "pipeline-runner-${UUID.random().toString().substring(0, 8)}"
        val queue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = false)

        val count = 25
        val ids = (1..count).map { i ->
            queue.enqueue(
                InternalJobConstructor(
                    definition = Json.parseToJsonElement("""{"i":$i}"""),
                    executor = PipelineCountingExecutor::class,
                )
            )
        }.toSet()
        val kv = kvFor(queueName)

        val runner = JobRunner(queue, 8, lockFactory)
        runner.run()
        try {
            // KV entries are deleted only by markComplete on a fully-complete job, so
            // a drained KV is proof the whole consume -> execute -> close path ran.
            withTimeout(90_000) {
                while (!kvDrained(kv, ids)) delay(100)
            }
        } finally {
            runner.shutdown()
        }

        assertTrue(kvDrained(kv, ids), "every job's state must be removed after the runner completes it")
        assertTrue(
            executions.get() >= count,
            "executor must run at least once per job (ran ${executions.get()} for $count jobs)",
        )
    }

    @OptIn(Internal::class)
    @Test
    fun `JobRunner terminally fails a job whose executor throws FailException`() = runBlocking {
        // FailException is the "permanent, do not retry" signal: the runner marks the
        // job failed with retry=false, which deletes its KV state and acks the message.
        // A drained KV therefore proves the FailException arm of execute() ran.
        provides<PipelineFailingExecutor> { PipelineFailingExecutor() }

        val queueName = "pipeline-fail-${UUID.random().toString().substring(0, 8)}"
        val queue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = false)

        val id = queue.enqueue(
            InternalJobConstructor(
                definition = Json.parseToJsonElement("{}"),
                executor = PipelineFailingExecutor::class,
            )
        )
        val kv = kvFor(queueName)

        val runner = JobRunner(queue, 4, lockFactory)
        runner.run()
        try {
            withTimeout(60_000) {
                while (!kvDrained(kv, setOf(id))) delay(100)
            }
        } finally {
            runner.shutdown()
        }

        assertTrue(kvDrained(kv, setOf(id)), "a FailException job must be terminally failed and its state removed")
    }

    @OptIn(Internal::class)
    @Test
    fun `busy workers leave further deliveries pending in NATS`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)
        provides<PipelineWaitingExecutor> { PipelineWaitingExecutor(started, finish, executions) }
        val queueName = "pipeline-busy-${UUID.random().toString().substring(0, 8)}"
        val queue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = false)
        val ids = (1..3).map {
            queue.enqueue(InternalJobConstructor(Json.parseToJsonElement("{}"), PipelineWaitingExecutor::class))
        }
        val kv = kvFor(queueName)
        val runner = JobRunner(queue, 1, lockFactory)
        runner.run()
        try {
            withTimeout(5_000) { started.await() }
            delay(300)
            val consumer = natsPool.systemConnection().jetStreamManagement()
                .getConsumerInfo("jobs-$queueName", "jobs-$queueName")
            assertEquals(1L, consumer.numAckPending, "Only the executing job may own a delivery")
            for (id in ids.drop(1)) {
                val entry = kv.get(id.toString())
                val state = json.decodeFromString(SerializedJob.serializer(), requireNotNull(entry?.valueAsString))
                assertEquals(JobStatus.PENDING, state.status)
            }
            finish.complete(Unit)
            withTimeout(15_000) {
                while (!kvDrained(kv, ids.toSet())) delay(50)
            }
            assertEquals(ids.size, executions.get(), "Each job must execute exactly once")
        } finally {
            runner.shutdown()
            finish.complete(Unit)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `the background expiry sweep runs when enabled`() = runBlocking {
        // processExpiredJobs = true starts the init GlobalScope sweep loop, which calls
        // ensureInitialized() then checkForExpiredJobs() on a real stream.
        val queueName = "pipeline-sweep-${UUID.random().toString().substring(0, 8)}"
        val queue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = true)

        val id = queue.enqueue(
            InternalJobConstructor(definition = Json.parseToJsonElement("{}"), executor = PipelineNoopExecutor::class)
        )
        // Give the background loop time to run at least one sweep pass.
        Thread.sleep(1500)

        // A healthy, freshly-enqueued job is not stale, so the sweep leaves it dequeueable.
        val dequeued = withTimeout(30_000) { queue.dequeue() }
        assertEquals(id, dequeued?.getId())
        queue.markComplete(dequeued!!)
    }

    @OptIn(Internal::class)
    @Test
    fun `dequeue drains orphaned messages instead of stalling on them`() = runBlocking {
        val queueName = "pipeline-orphans-${UUID.random().toString().substring(0, 8)}"
        val queue = NatsJobQueue(queueName, natsPool, json, lockFactory, processExpiredJobs = false)

        // Warm up to create the stream + durable consumer, then drain it.
        val warmupId = queue.enqueue(
            InternalJobConstructor(definition = Json.parseToJsonElement("{}"), executor = PipelineNoopExecutor::class)
        )
        val warmup = queue.dequeue()
        assertEquals(warmupId, warmup?.getId())
        queue.markComplete(warmup!!)

        // Flood the WorkQueue stream with orphaned messages: valid job ids that have NO
        // KV state — exactly what the re-queue sweep leaves behind once a job finishes.
        val js = runBlocking { natsPool.systemConnection() }.jetStream()
        repeat(30) {
            js.publish("jobs.$queueName", UUID.random().toString().toByteArray())
        }

        // Then one real job, published after all of the orphans.
        val realId = queue.enqueue(
            InternalJobConstructor(definition = Json.parseToJsonElement("""{"real":true}"""), executor = PipelineNoopExecutor::class)
        )

        // A SINGLE dequeue() call must skip past all 30 orphans and return the real
        // job. Before the fix it acked the first orphan and returned null, leaving the
        // real job buried behind the dead backlog while the fetcher backed off to ~1s.
        val dequeued = withTimeout(30_000) { queue.dequeue() }
        assertEquals(realId, dequeued?.getId(), "dequeue must drain orphans and surface the real job in one call")
        queue.markComplete(dequeued!!)
    }
}

class PipelineNoopExecutor : JobExecutor {
    override suspend fun execute() {}
}

class PipelineCountingExecutor(private val executions: AtomicInteger) : JobExecutor {
    override suspend fun execute() {
        executions.incrementAndGet()
    }
}

class PipelineWaitingExecutor(
    private val started: CompletableDeferred<Unit>,
    private val finish: CompletableDeferred<Unit>,
    private val executions: AtomicInteger,
) : JobExecutor {
    override suspend fun execute() {
        executions.incrementAndGet()
        started.complete(Unit)
        finish.await()
    }
}

class PipelineFailingExecutor : JobExecutor {
    override suspend fun execute() {
        throw bosca.sharedqueue.jobs.FailException("permanent failure")
    }
}
