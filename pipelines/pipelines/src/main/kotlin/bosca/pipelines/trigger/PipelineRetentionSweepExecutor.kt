package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.service.PipelineRunService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Runs the run-retention sweep on bosca-runner: soft-deletes terminal
 * run-state rows and deletes old run-history rows past their configured windows, bounding unbounded
 * growth of the run tables. Registered as a cron-scheduled job by
 * [bosca.pipelines.installer.PipelineScheduledJobsInstaller].
 */
@JobDefinition(
    definition = PipelineRetentionSweepJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = PipelineRetentionSweepExecutor.NAME,
    displayName = "Pipeline Run Retention Sweep",
)
class PipelineRetentionSweepExecutor(
    private val pipelineRunService: PipelineRunService,
) : AbstractJobExecutor<PipelineRetentionSweepJob>(PipelineRetentionSweepJob.serializer()) {

    override suspend fun execute() {
        val reaped = pipelineRunService.purgeExpiredRuns()
        if (reaped > 0) log.info("pipeline retention sweep reaped {} row(s)", reaped)
    }

    companion object {
        const val NAME = "pipeline-retention-sweep"
        private val log = LoggerFactory.getLogger(PipelineRetentionSweepExecutor::class.java)
    }
}
