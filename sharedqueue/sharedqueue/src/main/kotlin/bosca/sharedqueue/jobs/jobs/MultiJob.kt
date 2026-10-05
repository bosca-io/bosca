package bosca.sharedqueue.jobs.jobs

import bosca.di.provide
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.sharedqueue.jobs.enqueue
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent
import bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel
import bosca.sharedqueue.jobs.enqueueLater
import bosca.sharedqueue.jobs.job
import bosca.sharedqueue.jobs.jobQueue
import bosca.sharedqueue.jobs.prepare
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration

@Serializable
data class MultiJob(
    val jobs: List<MultiJobJob>,
    val id: UUID? = null,
    val version: Int? = null,
    val languageTag: String? = null,
    val type: String? = null
) : IJobDefinition

@Serializable
data class MultiJobJob(
    val name: String,
    val type: String,
    val configuration: JsonElement? = null
)

@JobDefinition(MultiJob::class, JobQueueNames.commonJobQueue, "multi-job", displayName = "Multi-Job")
class MultiJobExecutor : AbstractJobExecutor<MultiJob>(MultiJob.serializer()) {

    override suspend fun execute() {
        val definition = getJobDefinition()
        val currentJob = job()
        val jobQueue = jobQueue()
        definition.jobs.forEach {
            if (it.type != definition.type) return@forEach
            val configuration = JsonObject(currentJob.internalDefinition.jsonObject + (it.configuration?.jsonObject ?: JsonObject(emptyMap())))
            val enqueuer = provide<JobConfigurationEnqueuer>(name = it.name)
            val newJob = enqueuer.prepare(configuration)
            // Idempotency across retries: markFailed persists `children` to
            // KV, so a retry that re-enters execute() would otherwise append a
            // second copy of every fan-out child. Dedup on the (executor,
            // definition) pair — identical to "same fan-out entry from the
            // same config" — so retries converge on the intended child set
            // while legitimately distinct siblings that happen to share an
            // executor class (different definitions) are still added.
            if (currentJob.getChildren().any {
                    it.executor == newJob.executor && it.getDefinition() == newJob.getDefinition()
                }) {
                return@forEach
            }
            currentJob.addChild(newJob)
            jobQueue.setJob(currentJob)
            // Publish a synthetic enqueue event for the fan-out child so the admin
            // job-history view can render it under the parent immediately. Children
            // are not really on the queue yet — RunChildOnCompleteListener enqueues
            // them when the parent completes — but users want to see what's going to
            // run, not discover the child list one-row-at-a-time as the parent finishes.
            // The later real enqueue from RunChildOnCompleteListener emits a second
            // event that refreshPendingHistory collapses onto this same row.
            emitChildEnqueueEvent(parent = currentJob, child = newJob, childConfigName = it.name)
        }
    }

    private suspend fun emitChildEnqueueEvent(parent: Job, child: Job, childConfigName: String) {
        // A plain try/catch avoids the subtlety of calling a suspend function inside
        // `runCatching`'s non-suspend `block` parameter. Not every bootstrap registers
        // the enqueue-event channel (unit tests reset the ProviderRegistry), so we
        // treat the lookup failure as "no channel — nothing to forward" rather than
        // propagating it.
        val channel: JobEnqueueEventChannel = try {
            provide()
        } catch (_: Exception) {
            return
        }
        try {
            channel.emit(
                JobEnqueueEvent(
                    jobId = child.getId(),
                    executor = child.executor.qualifiedName ?: child.executor.simpleName ?: "unknown",
                    // `executorName` is the DI lookup key — preserve whatever the enqueuer
                    // put on the child. Do NOT synthesize a value, or we could make the
                    // subsequent real enqueue's `newExecutor()` fail with
                    // MissingProviderException.
                    executorName = child.executorName,
                    // `displayName` is the cosmetic label for the admin UI. Prefer the
                    // one the child already carries (from its own `@JobDefinition(displayName)`
                    // via the KSP-generated enqueuer); fall back to the fan-out config's
                    // `name` so the row shows "transition-metadata" rather than the FQN.
                    displayName = child.displayName ?: childConfigName,
                    queue = null,
                    enqueuedAt = OffsetDateTime.now(),
                    delayed = false,
                    delayedUntil = null,
                    definition = child.getDefinition(),
                    context = child.getContext(),
                    parentJobId = parent.getId(),
                )
            )
        } catch (e: Exception) {
            log.warn("Failed to emit fan-out enqueue event for child ${child.getId()} of ${parent.getId()}", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MultiJobExecutor::class.java)
    }
}

class MultiJobExecutorEnqueuer : JobConfigurationEnqueuer {
    override val queueName: String = JobQueueNames.commonJobQueue

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override suspend fun prepare(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job {
        val json = provide<Json>()
        val definition = json.decodeFromJsonElement<MultiJob>(configuration)
        return definition.prepare(initializer = initializer)
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override suspend fun enqueue(configuration: JsonElement, initializer: suspend Job.() -> Unit): Job {
        val json = provide<Json>()
        val definition = json.decodeFromJsonElement<MultiJob>(configuration)
        return definition.enqueue(initializer = initializer)
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override suspend fun enqueueLater(
        configuration: JsonElement,
        timeout: Duration,
        initializer: suspend Job.() -> Unit,
    ): Job {
        val json = provide<Json>()
        val definition = json.decodeFromJsonElement<MultiJob>(configuration)
        return definition.enqueueLater(timeout = timeout, initializer = initializer)
    }

    override suspend fun queue(): JobQueue = provide<JobQueue>(name = JobQueueNames.commonJobQueue)
}

// Keep in lockstep with the `displayName` on [MultiJobExecutor]'s @JobDefinition
// so admin UI rows created through these hand-rolled extensions render with the
// same label as rows created through the KSP-generated enqueuer. Note: this is
// the UI-facing displayName, NOT executorName — the latter is a DI lookup key
// and setting it to a value without a matching named provider would make
// dequeue fail at `Job.newExecutor()`.
private const val MULTI_JOB_DISPLAY_NAME = "Multi-Job"

suspend fun MultiJob.prepare(): Job = prepare(MultiJobExecutor::class, displayName = MULTI_JOB_DISPLAY_NAME)

suspend fun MultiJob.prepare(initializer: suspend Job.() -> Unit): Job =
    prepare(MultiJobExecutor::class, displayName = MULTI_JOB_DISPLAY_NAME, initializer = initializer)

suspend fun MultiJob.enqueue(): Job {
    val jobQueue = provide<JobQueue>(name = JobQueueNames.commonJobQueue)
    return enqueue(jobQueue, MultiJobExecutor::class, displayName = MULTI_JOB_DISPLAY_NAME)
}

suspend fun MultiJob.enqueue(initializer: suspend Job.() -> Unit): Job {
    val jobQueue = provide<JobQueue>(name = JobQueueNames.commonJobQueue)
    return enqueue(jobQueue, MultiJobExecutor::class, displayName = MULTI_JOB_DISPLAY_NAME, initializer = initializer)
}

suspend fun MultiJob.enqueueLater(timeout: Duration): Job {
    val jobQueue = provide<JobQueue>(name = JobQueueNames.commonJobQueue)
    return enqueueLater(jobQueue, MultiJobExecutor::class, displayName = MULTI_JOB_DISPLAY_NAME, timeout = timeout)
}

suspend fun MultiJob.enqueueLater(initializer: suspend Job.() -> Unit = {}, timeout: Duration): Job {
    val jobQueue = provide<JobQueue>(name = JobQueueNames.commonJobQueue)
    return enqueueLater(
        jobQueue,
        MultiJobExecutor::class,
        displayName = MULTI_JOB_DISPLAY_NAME,
        timeout = timeout,
        initializer = initializer,
    )
}
