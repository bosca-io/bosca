package bosca.sharedqueue.jobs

import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.redis.RedisConnectionPool
import bosca.sharedqueue.jobs.enqueue.DefaultJobEnqueueEventChannel
import bosca.sharedqueue.jobs.enqueue.EventEmittingJobQueue
import bosca.sharedqueue.jobs.nats.NatsJobQueue
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.redis.RedisJobQueue
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
import io.mockk.mockk
import io.mockk.unmockkAll
import io.nats.client.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The two [JobQueueFactory] implementations share one decision: wrap the backend
 * queue in an [EventEmittingJobQueue] iff an enqueue-event channel was supplied,
 * otherwise return the raw backend queue. Both branches are asserted for each
 * backend. `processExpiredJobs = false` keeps the constructed queue from spawning
 * its background expiry sweep on the shared dispatcher.
 */
class JobQueueFactoriesTest {

    private val lockFactory = mockk<DistributedLockFactory>(relaxed = true)

    @AfterTest
    fun tearDown() = unmockkAll()

    // ----- NATS -----

    private fun natsFactory(channel: DefaultJobEnqueueEventChannel?) = NatsJobQueueFactory(
        nats = NatsConnectionPool(mockk<Connection>(relaxed = true)),
        json = Json,
        distributedLockFactory = lockFactory,
        enqueueEventChannel = channel,
        enqueueCallbacks = emptyList(),
        processExpiredJobs = false,
    )

    @Test
    fun `nats factory wraps in an event-emitting queue when a channel is present`() {
        val queue = natsFactory(DefaultJobEnqueueEventChannel()).create("q")
        assertTrue(queue is EventEmittingJobQueue)
    }

    @Test
    fun `nats factory returns the raw queue when no channel is present`() {
        val queue = natsFactory(null).create("q")
        assertTrue(queue is NatsJobQueue)
    }

    // ----- Redis -----

    private fun redisFactory(channel: DefaultJobEnqueueEventChannel?) = RedisJobQueueFactory(
        redisConnectionPool = mockk<RedisConnectionPool>(relaxed = true),
        json = Json,
        distributedLockFactory = lockFactory,
        enqueueEventChannel = channel,
        enqueueCallbacks = emptyList(),
        processExpiredJobs = false,
    )

    @Test
    fun `redis factory wraps in an event-emitting queue when a channel is present`() {
        val queue = redisFactory(DefaultJobEnqueueEventChannel()).create("q")
        assertTrue(queue is EventEmittingJobQueue)
    }

    @Test
    fun `redis factory returns the raw queue when no channel is present`() {
        val queue = redisFactory(null).create("q")
        assertTrue(queue is RedisJobQueue)
    }
}
