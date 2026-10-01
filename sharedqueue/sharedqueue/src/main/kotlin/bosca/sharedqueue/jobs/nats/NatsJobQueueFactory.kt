package bosca.sharedqueue.jobs.nats

import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobQueueRegistry
import bosca.sharedqueue.jobs.enqueue.EventEmittingJobQueue
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel

class NatsJobQueueFactory(
    private val nats: NatsConnectionPool,
    private val json: kotlinx.serialization.json.Json,
    private val distributedLockFactory: DistributedLockFactory,
    private val enqueueEventChannel: JobEnqueueEventChannel?,
    private val enqueueCallbacks: List<JobCallback>,
    private val processExpiredJobs: Boolean = true
) : JobQueueFactory {

    override fun create(name: String): JobQueue {
        val queue = NatsJobQueue(name, nats, json, distributedLockFactory, processExpiredJobs)
        val created = enqueueEventChannel?.let { EventEmittingJobQueue(queue, name, it, enqueueCallbacks) } ?: queue
        JobQueueRegistry.register(created)
        return created
    }
}