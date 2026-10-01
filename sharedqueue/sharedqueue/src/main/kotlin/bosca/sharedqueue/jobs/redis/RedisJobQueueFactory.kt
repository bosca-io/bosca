package bosca.sharedqueue.jobs.redis

import bosca.lock.DistributedLockFactory
import bosca.redis.RedisConnectionPool
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobQueueRegistry
import bosca.sharedqueue.jobs.enqueue.EventEmittingJobQueue
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import kotlinx.serialization.json.Json

class RedisJobQueueFactory(
    private val redisConnectionPool: RedisConnectionPool,
    private val json: Json,
    private val distributedLockFactory: DistributedLockFactory,
    private val enqueueEventChannel: JobEnqueueEventChannel?,
    private val enqueueCallbacks: List<JobCallback>,
    private val processExpiredJobs: Boolean = true
) : JobQueueFactory {

    override fun create(name: String): JobQueue {
        val queue = RedisJobQueue(name, redisConnectionPool, json, distributedLockFactory, processExpiredJobs)
        val created = enqueueEventChannel?.let { EventEmittingJobQueue(queue, name, it, enqueueCallbacks) } ?: queue
        JobQueueRegistry.register(created)
        return created
    }
}