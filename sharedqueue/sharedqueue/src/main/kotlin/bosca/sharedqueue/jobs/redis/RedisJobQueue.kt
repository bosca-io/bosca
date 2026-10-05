@file:OptIn(ExperimentalTime::class, ExperimentalLettuceCoroutinesApi::class, ExperimentalSerializationApi::class)

package bosca.sharedqueue.jobs.redis

import bosca.cache.withRequestCache
import bosca.db.ConnectionManagerCallback
import bosca.db.connectionOrNull
import bosca.db.withConnectionManager
import bosca.lock.DistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisScriptExecutor
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.JobsDispatcher
import bosca.sharedqueue.jobs.LockAcquisitionException
import bosca.sharedqueue.jobs.SerializedJob
import bosca.sharedqueue.jobs.deserialize
import bosca.sharedqueue.jobs.serialize
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

internal class RedisJobQueue(
    private val queue: String,
    private val redisConnectionPool: RedisConnectionPool,
    private val json: Json,
    private val distributedLockFactory: DistributedLockFactory,
    processExpiredJobs: Boolean = true
) : JobQueue {

    override val name: String get() = queue

    private val expiredJobsQueueExecutor = RedisScriptExecutor(redisConnectionPool, expirationScript)
    private val dequeueExecutor = RedisScriptExecutor(redisConnectionPool, dequeueScript)
    private val enqueueIfAbsentExecutor = RedisScriptExecutor(redisConnectionPool, enqueueIfAbsentScript)

    init {
        if (processExpiredJobs) {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(JobsDispatcher) {
                while (true) {
                    try {
                        checkForExpiredJobs()
                    } catch (e: Exception) {
                        log.warn("Failed to evict expired items: {}", e.message)
                    }
                    delay(30_000.milliseconds)
                }
            }
        }
    }

    private fun Job.verifyLocked() {
        val lock = lock ?: error("Job $id has no lock")
        if (!lock.isHeld) error("Job $id lock is not held")
    }

    private val Job.isLockHeld: Boolean
        get() {
            val lock = lock ?: return false
            return lock.isHeld
        }

    override suspend fun <T> getJob(id: UUID, block: suspend (Job?) -> T): T = withContext(JobsDispatcher) {
        internalGetJob(id, 60_000, true, block)
    }

    private suspend fun <T> internalGetJob(id: UUID, lockTimeout: Long, releaseLock: Boolean, block: suspend (Job?) -> T): T = withContext(JobsDispatcher) {
        val lock = distributedLockFactory.create("job:lock:$id")
        if (!lock.acquire(lockTimeout, lockTimeout)) {
            error("Failed to acquire lock for job $id")
        }
        try {
            val connection = redisConnectionPool.connection()
            val serializedJob = try {
                connection.coroutines().hget(RedisValues.state(queue), id.toString())?.let {
                    json.decodeFromString(SerializedJob.serializer(), it)
                }
            } finally {
                redisConnectionPool.release(connection)
            }
            if (serializedJob == null) error("Job $id not found")
            val job = serializedJob.deserialize()
            job.lock = lock
            val result = block(job)
            result
        } finally {
            if (releaseLock) lock.release()
        }
    }

    override suspend fun setJob(job: Job): Unit = withContext(JobsDispatcher) {
        job.verifyLocked()
        val txn = RedisTransaction(json)
        txn.addOp(RedisTransactionOp.SetState(queue, job.id, job.serialize()))
        txn.execute()
    }

    override suspend fun checkForExpiredJobs(time: Long) {
        processExpiredJobsForQueue(time)
    }

    override suspend fun expireAllJobs() {
        processExpiredJobsForQueue(Long.MAX_VALUE)
    }

    override suspend fun markFailed(job: Job, exception: Exception, retry: Boolean): Unit = withContext(JobsDispatcher) {
        var job = job
        try {
            if (!job.isLockHeld) {
                log.error("Job ${job.id} not locked, trying to acquire lock")
                job = internalGetJob(job.id, 60_000, false) { it ?: error("Job ${job.id} not found") }
            }
            val txn = RedisTransaction(json)
            job.failures++
            // FAILED is retryable; the final failure (no more retries) is the terminal
            // FAILED_AND_COMPLETE — which counts as complete for a parent and fires runOnFailure children.
            val terminal = !retry || job.failures >= job.maxFailures
            job.setStatus(if (terminal) JobStatus.FAILED_AND_COMPLETE else JobStatus.FAILED)
            txn.addOp(RedisTransactionOp.RemoveRunning(queue, job.id))
            txn.addOp(RedisTransactionOp.RemovePending(queue, job.id))
            if (terminal) {
                log.warn("Marking job ${job.id} - ${job.executor} - ${job.executorName} - as failed, not retrying")
                txn.addOp(RedisTransactionOp.RemoveState(queue, job.id))
            } else {
                txn.addOp(RedisTransactionOp.QueueLater(queue, job.serialize(), 30 * job.failures))
                log.warn("Marking job ${job.id} - ${job.executor} - ${job.executorName} - as failed, retrying")
            }
            txn.execute()
            log.warn("Marked job ${job.id} - ${job.executor} - ${job.executorName} - as failed: ${exception.message}")
        } finally {
            job.lock?.release()
        }
        withRequestCache {
            withConnectionManager {
                job.callbacks.forEach {
                    it.newListener().onStatusChanged(job, job.status, exception.stackTraceToString())
                }
            }
        }
    }

    override suspend fun markComplete(job: Job): Unit = withContext(JobsDispatcher) {
        var job = job
        try {
            if (!job.isLockHeld) {
                log.error("Job ${job.id} not locked, trying to acquire lock")
                job = internalGetJob(job.id, 60_000, false) { it ?: error("Job ${job.id} not found") }
            }
            job.setStatus(JobStatus.COMPLETE)
            val txn = RedisTransaction(json)
            txn.addOp(RedisTransactionOp.RemoveRunning(queue, job.id))
            txn.addOp(RedisTransactionOp.RemovePending(queue, job.id))
            if (job.isFullyComplete()) {
                txn.addOp(RedisTransactionOp.RemoveState(queue, job.id))
            } else {
                txn.addOp(RedisTransactionOp.SetState(queue, job.id, job.serialize()))
            }
            txn.execute()
            log.info("Marked job ${job.id} - ${job.executor} - ${job.executorName} - Fully Complete = ${job.isFullyComplete()} as complete")
        } finally {
            job.lock?.release()
        }
        withRequestCache {
            withConnectionManager {
                job.callbacks.forEach {
                    it.newListener().onStatusChanged(job, JobStatus.COMPLETE)
                }
            }
        }
    }

    override suspend fun markCancelled(id: UUID) {
        val txn = RedisTransaction(json)
        txn.addOp(RedisTransactionOp.RemoveRunning(queue, id))
        txn.addOp(RedisTransactionOp.RemovePending(queue, id))
        txn.addOp(RedisTransactionOp.RemoveState(queue, id))
        txn.execute()
    }

    override suspend fun checkin(job: Job, lockRenew: Long): Boolean = withContext(JobsDispatcher) {
        val lock = job.lock ?: return@withContext false
        if (!lock.isHeld || !lock.renew(lockRenew)) return@withContext false
        val txn = RedisTransaction(json)
        txn.addOp(RedisTransactionOp.Checkin(queue, job.id))
        txn.execute()
        true
    }

    override suspend fun setDefinition(job: Job, definition: JsonElement): Unit = withContext(JobsDispatcher) {
        job.definition = definition
        setJob(job)
    }

    override suspend fun enqueue(job: Job): UUID {
        job.setStatus(JobStatus.PENDING, true)
        job.queueName = name
        val serializedJob = job.serialize()
        dbRunAfterCommit { enqueue(job, serializedJob) }
        return serializedJob.id
    }

    override suspend fun enqueueIfAbsent(job: Job): UUID {
        require(job.id != UUID.NIL) { "enqueueIfAbsent requires a caller-assigned persistent job ID" }
        job.setStatus(JobStatus.PENDING, true)
        job.queueName = name
        val serializedJob = job.serialize()
        dbRunAfterCommit { enqueueIfAbsent(serializedJob) }
        return serializedJob.id
    }

    private suspend fun enqueueIfAbsent(serializedJob: SerializedJob) {
        val jobId = serializedJob.id.toString()
        val inserted = enqueueIfAbsentExecutor.execute<Long>(
            ScriptOutputType.INTEGER,
            arrayOf(RedisValues.state(queue), RedisValues.pending(queue)),
            jobId,
            json.encodeToString(SerializedJob.serializer(), serializedJob),
        ) == 1L
        if (inserted) {
            log.debug(
                "Enqueued persistent job if absent: {} : {} -> {}",
                jobId,
                serializedJob.executor,
                serializedJob.definition,
            )
        } else {
            log.debug("Persistent job {} already exists in queue {}; leaving it unchanged", jobId, queue)
        }
    }

    private suspend fun enqueue(job: Job, serializedJob: SerializedJob) {
        val txn = RedisTransaction(json)
        txn.addOp(RedisTransactionOp.SetState(queue, job.id, serializedJob))
        txn.addOp(RedisTransactionOp.Queue(queue, serializedJob))
        txn.execute()
        log.debug("Enqueued job: {} : {} : {} -> {}", serializedJob.id, job.executor, job.executorName, serializedJob.definition)
    }

    override suspend fun enqueueLater(job: Job, timeout: Duration): UUID {
        if (!timeout.isPositive()) {
            return enqueue(job)
        }
        job.setStatus(JobStatus.PENDING, true)
        job.queueName = name
        val serializedJob = job.serialize()
        dbRunAfterCommit { enqueueLater(job, serializedJob, timeout) }
        return serializedJob.id
    }

    private suspend fun enqueueLater(job: Job, serializedJob: SerializedJob, timeout: Duration) {
        if (!timeout.isPositive()) {
            enqueue(job, serializedJob)
            return
        }
        val txn = RedisTransaction(json)
        txn.addOp(RedisTransactionOp.SetState(queue, job.id, serializedJob))
        txn.addOp(RedisTransactionOp.QueueLater(queue, serializedJob, timeout.inWholeSeconds.toInt()))
        txn.execute()
        log.info("Enqueued job later: ${serializedJob.id} -> ${serializedJob.definition} :: $timeout")
    }

    private suspend fun dbRunAfterCommit(block: suspend () -> Unit) {
        val dbConnection = connectionOrNull()
        if (dbConnection != null && dbConnection.inTransaction) {
            dbConnection.addCallback(object : ConnectionManagerCallback {
                override suspend fun onCommit() {
                    block()
                }
                override suspend fun onRelease() {
                }
            })
        } else {
            block()
        }
    }

    private suspend fun dequeueJobId(pendingKey: String, runningKey: String): String? {
        val now = Clock.System.now().epochSeconds.toString()
        val delay = 1800.toString()
        return dequeueExecutor.execute<String>(
            ScriptOutputType.VALUE,
            arrayOf(pendingKey, runningKey),
            now, delay
        )?.takeIf { it.isNotEmpty() }
    }

    override suspend fun dequeue(): Job? = withContext(JobsDispatcher) {
        val pendingKey = RedisValues.pending(queue)
        val runningKey = RedisValues.running(queue)
        val jobId = dequeueJobId(pendingKey, runningKey) ?: return@withContext null
        val lock = distributedLockFactory.create("job:lock:$jobId")
        if (!lock.acquire(120_000, 240_000)) {
            log.warn("Failed to acquire lock for job {}, pushing back to pending", jobId)
            val connection = redisConnectionPool.connection()
            try {
                connection.coroutines().zrem(runningKey, jobId)
                connection.coroutines().rpush(pendingKey, jobId)
            } finally {
                redisConnectionPool.release(connection)
            }
            return@withContext null
        }
        val connection = redisConnectionPool.connection()
        try {
            val data = connection.coroutines().hget(RedisValues.state(queue), jobId)
            if (data == null) {
                log.warn("Job not found in state: $jobId")
                connection.coroutines().zrem(runningKey, jobId)
                lock.release()
                return@withContext null
            }
            val serializedJob = json.decodeFromString(SerializedJob.serializer(), data)
            val job = serializedJob.deserialize()
            job.lock = lock
            job.setStatus(JobStatus.RUNNING)
            val txn = RedisTransaction(json)
            txn.addOp(RedisTransactionOp.SetState(queue, job.id, job.serialize()))
            txn.execute()
            job
        } catch (e: Exception) {
            log.error("Failed to dequeue job: $jobId, pushing back to pending", e)
            try {
                connection.coroutines().zrem(runningKey, jobId)
                connection.coroutines().rpush(pendingKey, jobId)
            } catch (pushBackError: Exception) {
                log.error("Failed to push job {} back to pending", jobId, pushBackError)
            }
            lock.release()
            null
        } finally {
            redisConnectionPool.release(connection)
        }
    }

    private suspend fun processExpiredJobsForQueue(time: Long) {
        val pending = RedisValues.pending(queue)
        val running = RedisValues.running(queue)
        val timeBuffer = (time / 1000).toString()
        val result = expiredJobsQueueExecutor.execute<Long>(ScriptOutputType.INTEGER, arrayOf(pending, running), timeBuffer) ?: 0
        if (result > 0) {
            log.info("Found expired jobs: $result in queue: $queue")
        }
    }

    private suspend fun RedisTransaction.execute() {
        val connection = redisConnectionPool.connection()
        try {
            execute(connection)
        } finally {
            redisConnectionPool.release(connection)
        }
    }

    override suspend fun clearJobLock(id: UUID) {
        distributedLockFactory.forceRelease("job:lock:$id")
    }

    override suspend fun clearAllJobLocks(): Unit = withContext(JobsDispatcher) {
        val connection = redisConnectionPool.connection()
        try {
            val keys = connection.coroutines().hkeys(RedisValues.state(queue))
            keys.collect { jobId ->
                distributedLockFactory.forceRelease("job:lock:$jobId")
            }
        } finally {
            redisConnectionPool.release(connection)
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(RedisJobQueue::class.java)

        private val enqueueIfAbsentScript = """
        local state_hash   = tostring(KEYS[1])
        local pending_list = tostring(KEYS[2])
        local job_id       = tostring(ARGV[1])
        local job_state    = tostring(ARGV[2])

        if redis.call('HEXISTS', state_hash, job_id) == 1 then
            return 0
        end

        redis.call('HSET', state_hash, job_id, job_state)
        redis.call('RPUSH', pending_list, job_id)
        return 1
        """.trimIndent()

        private val dequeueScript = """
        local job_queue     = tostring(KEYS[1])
        local running_queue = tostring(KEYS[2])

        local now   = tonumber(ARGV[1]) -- Current timestamp
        local delay = tonumber(ARGV[2]) -- Expiration delay

        local item = redis.call('LPOP', job_queue)
        if item then
            local expire_time = now + delay
            redis.call('ZADD', running_queue, expire_time, item)
            redis.call('INCR', 'sharedqueue::dequeued::count')
            return tostring(item)
        else
            return nil -- Nothing to pop
        end
        """.trimIndent()

        private val expirationScript = """
        local pending_queue = tostring(KEYS[1])
        local running_queue = tostring(KEYS[2])
        local current_timestamp = tonumber(ARGV[1])
        local expired_items = redis.call('ZRANGEBYSCORE', running_queue, 0, current_timestamp)
        if #expired_items > 0 then
            for i, item in ipairs(expired_items) do
                redis.call('RPUSH', pending_queue, item)
                redis.call('ZREM', running_queue, item)
                redis.call('INCR', 'sharedqueue::expired::count')
            end
        end
        return #expired_items
        """.trimIndent()
    }
}
