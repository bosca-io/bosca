package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.service.PipelineRunService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Runs the suspended-run sweep on bosca-runner: fails runs stuck
 * [bosca.pipelines.model.PipelineRunStatus.SUSPENDED] past their configured max lifetime — a backstop
 * for backing work that never resolves. Registered as a cron-scheduled job by
 * [bosca.pipelines.installer.PipelineScheduledJobsInstaller].
 */
@JobDefinition(
    definition = PipelineSuspendedSweepJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = PipelineSuspendedSweepExecutor.NAME,
    displayName = "Pipeline Suspended-Run Sweep",
)
class PipelineSuspendedSweepExecutor(
    private val pipelineRunService: PipelineRunService,
) : AbstractJobExecutor<PipelineSuspendedSweepJob>(PipelineSuspendedSweepJob.serializer()) {

    override suspend fun execute() {
        val swept = pipelineRunService.sweepStuckSuspended()
        if (swept > 0) log.warn("pipeline suspended-run sweep failed {} stuck run(s)", swept)
    }

    companion object {
        const val NAME = "pipeline-suspended-sweep"
        private val log = LoggerFactory.getLogger(PipelineSuspendedSweepExecutor::class.java)
    }
}
