package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.node.PipelineRunDriveListener
import bosca.pipelines.service.PipelineRunService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.job

/**
 * Drives an **on-demand run** (a manual / API run created by [PipelineRunService.startOnDemand]) as its
 * own run job. The initiating request thread is not a run job, so an on-demand run has
 * nothing for a suspending node to attach its backing work to; running it here gives it a real run job.
 *
 * Carries the run job's drive listener so the run's async nodes resume durably — this job is, for an
 * on-demand run, exactly what [PipelineRunJobExecutor]'s job is for a triggered run and
 * [PipelineChildRunJobExecutor]'s is for a child run.
 */
@JobDefinition(
    definition = PipelineManualRunJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = PipelineManualRunJobExecutor.NAME,
)
class PipelineManualRunJobExecutor(
    private val pipelineRunService: PipelineRunService,
) : AbstractJobExecutor<PipelineManualRunJob>(PipelineManualRunJob.serializer()) {

    override suspend fun execute() {
        // This job IS the on-demand run's run job — carry the drive listener so the run's backing
        // children resume it; then drive the already-created run from its stored row.
        job().addCallback(JobCallback(listener = PipelineRunDriveListener::class))
        pipelineRunService.process(getJobDefinition().runId)
    }

    companion object {
        /** Job name + the `JobConfigurationEnqueuer` provider name an on-demand run is enqueued by. */
        const val NAME = "pipeline-manual-run"
    }
}
