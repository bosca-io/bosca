package bosca.sharedqueue.jobs.postgres

import bosca.db.ConnectionPool
import bosca.sharedqueue.jobs.*
import bosca.sharedqueue.jobs.enqueue.EventEmittingJobQueue
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import kotlinx.serialization.json.Json

/**
 * Creates PostgreSQL job queues on [pool], normally the infrastructure pool so claims, leases, and
 * state changes never wait on a request transaction's connection. Enqueues inside a transaction
 * still join that transaction's connection.
 */
class PostgresJobQueueFactory(
    private val pool: ConnectionPool,
    private val json: Json,
    private val enqueueEventChannel: JobEnqueueEventChannel?,
    private val enqueueCallbacks: List<JobCallback>,
) : JobQueueFactory {
    override fun create(name: String): JobQueue {
        val queue = PostgresJobQueue(name, pool, json)
        val decorated = enqueueEventChannel?.let { EventEmittingJobQueue(queue, name, it, enqueueCallbacks) } ?: queue
        JobQueueRegistry.register(decorated)
        return decorated
    }
}
