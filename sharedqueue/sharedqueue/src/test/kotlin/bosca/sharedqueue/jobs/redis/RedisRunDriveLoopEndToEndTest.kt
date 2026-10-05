@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.sharedqueue.jobs.redis

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.core.annotations.Internal
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobExecutor
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.SerializedJob
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.jobQueue
import bosca.sharedqueue.jobs.listeners.NotifyParentListener
import bosca.test.resources.SharedValkeyContainer
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves the "run = a job, driven by the parent's `onChildStatusChanged` listener" model
 * against a **real Redis queue** — exercising the live mechanism the pipeline run is
 * built on: the parent monitors its immediate children, and on each child's terminal status its drive
 * listener enqueues the next child while the parent is held locked. The parent stays not-fully-complete
 * (its state persists) while a child is outstanding, and becomes fully complete (state removed) exactly
 * when its last child finishes with no successor — through real enqueue/dequeue/markComplete,
 * serialization of the callback across dequeues, and `NotifyParentListener`'s lock-held bubble-up.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisRunDriveLoopEndToEndTest {

    private lateinit var redisContainer: SharedValkeyContainer
    private lateinit var redisPool: RedisConnectionPool
    private lateinit var lockFactory: RedisDistributedLockFactory
    private lateinit var queue: RedisJobQueue
    private val json = Json

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
        // The parent's child-monitoring callbacks must resolve via JobCallback.newListener().
        provides<NotifyParentListener> { NotifyParentListener() }
        provides<StepDriveListener> { StepDriveListener() }

        queue = RedisJobQueue("redis-run-drive-test", redisPool, json, lockFactory)
        Thread.sleep(1000)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::redisContainer.isInitialized) redisContainer.stop()
    }

    private suspend fun readState(jobId: UUID): SerializedJob? {
        val connection = redisPool.connection()
        try {
            val data = connection.coroutines().hget(RedisValues.state("redis-run-drive-test"), jobId.toString())
                ?: return null
            return json.decodeFromString(SerializedJob.serializer(), data)
        } finally {
            redisPool.release(connection)
        }
    }

    /** markComplete fires the job's callbacks; NotifyParentListener inside reads the ambient queue. */
    private suspend fun completeInContext(job: Job) =
        withContext(queue.asCoroutineContext(job)) { queue.markComplete(job) }

    @OptIn(Internal::class)
    @Test
    fun `a parent driven by onChildStatusChanged stays open until its last child then fully completes`() = runBlocking {
        // The parent carries the drive listener (added while id == NIL, so it is locked).
        val parent = InternalJobConstructor(JsonNull, NoopExecutor::class)
        parent.addCallback(JobCallback(listener = StepDriveListener::class))
        val parentId = queue.enqueue(parent)

        // "Initial drive": dequeue the parent (locks it), attach its first child, run its own segment to
        // completion. runOnParentComplete=false — the drive enqueues children itself; the parent must not
        // re-run them when it completes.
        val drivenParent = queue.dequeue() ?: error("expected to dequeue the parent")
        assertEquals(parentId, drivenParent.getId())
        val child0 = InternalJobConstructor(JsonNull, NoopExecutor::class)
        child0.setContext(buildJsonObject { put("step", 0) })
        drivenParent.addChild(child0, runOnParentComplete = false)
        queue.enqueue(child0)
        completeInContext(drivenParent)

        // The parent finished its own segment but a child is outstanding -> NOT fully complete -> retained.
        assertNotNull(readState(parentId), "parent stays open while a child is outstanding")

        // Drive each child: completing it bubbles up to the parent, whose drive listener enqueues the
        // next child (keeping it open) until there is no successor.
        var completed = 0
        while (true) {
            val child = queue.dequeue() ?: break
            completeInContext(child)
            completed++
            if (completed < StepDriveListener.TOTAL_STEPS) {
                assertNotNull(readState(parentId), "parent still open after child #$completed (next child enqueued)")
            } else {
                assertNull(readState(parentId), "parent fully complete once its last child finished")
            }
        }

        assertEquals(StepDriveListener.TOTAL_STEPS, completed, "every segment's child ran exactly once")
        assertNull(readState(parentId), "the run job's state is gone once the run is done")
    }
}

private class NoopExecutor : JobExecutor {
    override suspend fun execute() {}
}

/**
 * Stands in for the run job's drive hook: on each child's terminal status, if more segments remain,
 * attach + enqueue the next child onto the (locked) parent. The parent is held locked when this fires,
 * so addChild/enqueue are legal and the fresh child keeps the parent open.
 */
private class StepDriveListener : JobListener {
    @OptIn(Internal::class)
    override suspend fun onChildStatusChanged(job: Job, child: Job, status: JobStatus) {
        val step = (child.getContext() as? JsonObject)?.get("step")?.jsonPrimitive?.int ?: return
        if (step + 1 >= TOTAL_STEPS) return
        val next = InternalJobConstructor(JsonNull, NoopExecutor::class)
        next.setContext(buildJsonObject { put("step", step + 1) })
        job.addChild(next, runOnParentComplete = false)
        jobQueue().enqueue(next)
    }

    companion object {
        const val TOTAL_STEPS = 3
    }
}
