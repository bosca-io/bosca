package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.model.PipelineRunLog
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Starts one **scheduled** pipeline run. The SchedulerService fires this
 * on the pipeline's cron cadence with the target pipeline id; the run is seeded with a small JSON
 * input carrying the fire time (`{ scheduledAt }`) and driven by [PipelineRunService] exactly like a
 * triggered run — so suspend/resume, the timeline, and history all apply. A scheduled pipeline should
 * therefore accept JSON input (it has no triggering event).
 *
 * Like the triggered-run executor, a failure before the run exists (pipeline gone) is recorded in run
 * history rather than rethrown, so one bad schedule cannot poison the queue; cancellation propagates.
 */
@JobDefinition(
    definition = PipelineScheduledRunJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = PipelineScheduledRunExecutor.NAME,
)
class PipelineScheduledRunExecutor(
    private val pipelineService: PipelineService,
    private val pipelineRunService: PipelineRunService,
) : AbstractJobExecutor<PipelineScheduledRunJob>(PipelineScheduledRunJob.serializer()) {

    private val log = LoggerFactory.getLogger(PipelineScheduledRunExecutor::class.java)

    override suspend fun execute() {
        val jobDef = getJobDefinition()
        val now = OffsetDateTime.now()
        val startNanos = System.nanoTime()
        try {
            val pipeline = pipelineService.get(jobDef.pipelineId)
                ?: error("Pipeline not found: ${jobDef.pipelineId}")
            val input = PipelineValue.ofJson(buildJsonObject { put("scheduledAt", now.toString()) })
            // Start + drive the scheduled run; shed (null, nothing runs) if the pipeline is at its
            // concurrency / rate cap.
            pipelineRunService.start(pipeline, input, EVENT_NAME, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Scheduled pipeline {} could not start", jobDef.pipelineId, e)
            try {
                pipelineRunService.recordLog(
                    PipelineRunLog(
                        pipelineId = jobDef.pipelineId,
                        eventName = EVENT_NAME,
                        outcome = bosca.pipelines.model.PipelineRunStatus.FAILED,
                        startedAt = now,
                        finishedAt = OffsetDateTime.now(),
                        durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                        errorMessage = e.message ?: e.toString(),
                    )
                )
            } catch (e2: Exception) {
                log.error("Failed to record scheduled run log for {}", jobDef.pipelineId, e2)
            }
        }
    }

    companion object {
        /** Job name + the SchedulerService ScheduledJob.jobName for cron-fired pipeline runs. */
        const val NAME = "pipeline-scheduled-run"

        /** Run-history `eventName` for scheduled runs. */
        const val EVENT_NAME = "schedule"
    }
}
