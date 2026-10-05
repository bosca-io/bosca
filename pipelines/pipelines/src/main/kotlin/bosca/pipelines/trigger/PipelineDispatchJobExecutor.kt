package bosca.pipelines.trigger

import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.service.PipelineService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

/**
 * Matches one event occurrence against triggered pipelines — on the runner, not the API server —
 * and enqueues a [PipelineRunJob] per match (per-pipeline isolation and run-history). Per-pipeline
 * enqueue failures are logged so one bad pipeline can't block the others; the lookup itself
 * propagates so the job retries on infrastructure failure.
 */
@JobDefinition(
    definition = PipelineDispatchJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = "pipeline-dispatch"
)
class PipelineDispatchJobExecutor(
    private val pipelineService: PipelineService,
) : AbstractJobExecutor<PipelineDispatchJob>(PipelineDispatchJob.serializer()) {

    private val log = LoggerFactory.getLogger(PipelineDispatchJobExecutor::class.java)

    override suspend fun execute() {
        val jobDef = getJobDefinition()
        val failures = mutableListOf<Exception>()
        pipelineService.triggeredFor(jobDef.eventName).forEach { pipeline ->
            try {
                PipelineRunJob(
                    pipelineId = pipeline.id,
                    eventName = jobDef.eventName,
                    eventPayload = jobDef.eventPayload,
                    eventCreated = jobDef.eventCreated,
                ).enqueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to enqueue pipeline {} for event {}", pipeline.id, jobDef.eventName, e)
                failures += e
            }
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }
}
