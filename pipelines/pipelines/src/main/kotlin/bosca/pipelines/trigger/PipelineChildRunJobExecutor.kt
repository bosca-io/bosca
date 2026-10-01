package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.node.PipelineRunDriveListener
import bosca.pipelines.service.PipelineRunService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.job

/**
 * Drives a RunPipeline/ForEach **child run** as a child job of the parent run job.
 * Driving the child here — rather than inline in the parent's suspend thunk — keeps the
 * parent run job open while the child is in flight and lets the child itself suspend durably; the child
 * reports its output back to the parent the usual way on completion.
 *
 * Carries the run job's drive listener so the child run's OWN async nodes resume durably — this job is,
 * for the child run, exactly what `PipelineRunJobExecutor`'s job is for a top-level run.
 */
@JobDefinition(
    definition = PipelineChildRunJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = PipelineChildRunJobExecutor.NAME,
)
class PipelineChildRunJobExecutor(
    private val pipelineRunService: PipelineRunService,
) : AbstractJobExecutor<PipelineChildRunJob>(PipelineChildRunJob.serializer()) {

    override suspend fun execute() {
        // This job IS the child run's run job — carry the drive listener so the child run's own backing
        // children resume it (the child run is durable in its own right).
        job().addCallback(JobCallback(listener = PipelineRunDriveListener::class))
        pipelineRunService.process(getJobDefinition().childRunId)
    }

    companion object {
        /** Job name + the `JobConfigurationEnqueuer` provider name a RunPipeline/ForEach enqueues a child run by. */
        const val NAME = "pipeline-child-run"
    }
}
