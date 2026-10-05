package bosca.sharedqueue.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlin.reflect.KClass
import kotlin.time.Duration

suspend inline fun <reified T : IJobDefinition> T.prepare(
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
): Job {
    val job = Job(
        definition = this,
        executor = executor,
        executorName = executorName,
        displayName = displayName,
    )
    return job
}

suspend inline fun <reified T : IJobDefinition> T.prepare(
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
    initializer: suspend Job.() -> Unit
): Job {
    val job = Job(
        definition = this,
        executor = executor,
        executorName = executorName,
        displayName = displayName,
    )
    job.initializer()
    return job
}

/**
 * Prepares a job with a caller-owned durable identifier.
 *
 * This is intended for outbox-style producers that persist the identifier before queue
 * publication and may need to republish the same logical job after a process failure.
 */
suspend inline fun <reified T : IJobDefinition> T.prepare(
    id: UUID,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
): Job {
    val job = Job(
        definition = this,
        executor = executor,
        executorName = executorName,
        displayName = displayName,
    )
    job.setPersistentId(id)
    return job
}

suspend inline fun <reified T : IJobDefinition> T.prepare(
    id: UUID,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
    initializer: suspend Job.() -> Unit,
): Job {
    val job = prepare(executor, executorName, displayName)
    job.initializer()
    job.setPersistentId(id)
    return job
}

suspend inline fun <reified T : IJobDefinition> T.enqueue(
    queue: JobQueue,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
): Job {
    val job = prepare(executor, executorName, displayName)
    queue.enqueue(job)
    return job
}

suspend inline fun <reified T : IJobDefinition> T.enqueue(
    queue: JobQueue,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
    initializer: suspend Job.() -> Unit
): Job {
    val job = prepare(executor, executorName, displayName, initializer)
    queue.enqueue(job)
    return job
}

suspend inline fun <reified T : IJobDefinition> T.enqueueLater(
    queue: JobQueue,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
    timeout: Duration,
): Job {
    val job = prepare(executor, executorName, displayName)
    queue.enqueueLater(job, timeout)
    return job
}

suspend inline fun <reified T : IJobDefinition> T.enqueueLater(
    queue: JobQueue,
    executor: KClass<out JobExecutor>,
    executorName: String? = null,
    displayName: String? = null,
    timeout: Duration,
    initializer: suspend Job.() -> Unit
): Job {
    val job = prepare(executor, executorName, displayName, initializer)
    queue.enqueueLater(job, timeout)
    return job
}
