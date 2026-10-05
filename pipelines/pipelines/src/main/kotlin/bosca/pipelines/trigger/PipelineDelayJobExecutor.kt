package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The durable-timer child job: re-queues itself via the platform's native
 * [DelayException] until its wake time, then completes. Its completion bubbles up to the run job, whose
 * drive listener resumes the parked timer node (the run/node correlation is in this job's context, set
 * by the timer node). Carries no service deps — the resume is driven by the run job, not here.
 */
@JobDefinition(
    definition = PipelineDelayJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = PipelineDelayJobExecutor.NAME,
)
class PipelineDelayJobExecutor : AbstractJobExecutor<PipelineDelayJob>(PipelineDelayJob.serializer()) {

    override suspend fun execute() {
        val remaining = getJobDefinition().wakeAtEpochMillis - System.currentTimeMillis()
        // Not due → re-queue after the remaining time (native DelayException re-delivery). Due → return,
        // which completes the job and lets the run job's drive listener resume the parked timer node.
        if (remaining > 0) throw DelayException(remaining.milliseconds)
    }

    companion object {
        /** Job name + the `JobConfigurationEnqueuer` provider name a timer node enqueues the delay by. */
        const val NAME = "pipeline-delay"
    }
}
