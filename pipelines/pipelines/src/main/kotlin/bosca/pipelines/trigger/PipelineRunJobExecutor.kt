package bosca.pipelines.trigger

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.events.catalog.EventCatalogRegistrar
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.model.PipelineRunLog
import bosca.pipelines.node.PipelineRunDriveListener
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.job
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Starts one triggered pipeline for one event occurrence. The typed event is rebuilt from the
 * payload via the Event Catalog's explicitly compiled serializer maps (native-safe); a durable run
 * is created and handed to [PipelineRunService], which drives evaluation under the configured
 * pipelines service account and persists the outcome (terminal status + `pipeline_run_log` history
 * on completion, or a checkpoint + scheduled backing work on suspension).
 *
 * This executor only does the work that precedes a run existing; a failure here (pipeline or
 * serializer missing, bad payload) is recorded directly in the run history since no run state was
 * created. Failures are recorded rather than rethrown so one bad pipeline cannot poison the queue;
 * cancellation always propagates.
 */
@JobDefinition(
    definition = PipelineRunJob::class,
    // The queue arg is the JobQueue PROVIDER name, not the physical queue name.
    queue = PipelinesJobQueueNames.jobQueue,
    name = "pipeline-run"
)
class PipelineRunJobExecutor(
    private val pipelineService: PipelineService,
    private val pipelineRunService: PipelineRunService,
    private val json: Json,
) : AbstractJobExecutor<PipelineRunJob>(PipelineRunJob.serializer()) {

    private val log = LoggerFactory.getLogger(PipelineRunJobExecutor::class.java)

    override suspend fun execute() {
        val jobDef = getJobDefinition()
        val startedAt = OffsetDateTime.now()
        val startNanos = System.nanoTime()
        try {
            // This job IS the run's job ("run = a job"): carry the drive listener so it
            // monitors its immediate backing-job children and resumes the run as each completes. Added
            // before the drive (and persisted when this job checkpoints) so it is present when those
            // children later bubble their terminal status up to this job.
            job().addCallback(JobCallback(listener = PipelineRunDriveListener::class))

            val pipeline = pipelineService.get(jobDef.pipelineId)
                ?: error("Pipeline not found: ${jobDef.pipelineId}")
            val serializer = eventSerializer(jobDef.eventName)
                ?: error("No catalogued serializer for event ${jobDef.eventName}")

            @Suppress("UNCHECKED_CAST")
            val typed = serializer as KSerializer<Any>
            val event = json.decodeFromJsonElement(typed, jobDef.eventPayload)
            val input = PipelineValue.of(event, typed)

            // Start the durable run: snapshots the graph + seed and drives it to completion or its first
            // suspend, persisting the outcome — unless the pipeline's concurrency / rate caps shed it,
            // in which case start returns null and nothing runs.
            pipelineRunService.start(pipeline, input, jobDef.eventName, jobDef.eventCreated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Failure BEFORE the run existed (pipeline/serializer missing, bad payload): no run state
            // to mark terminal, so record it directly in the run history.
            log.error("Pipeline {} could not start for event {}", jobDef.pipelineId, jobDef.eventName, e)
            try {
                pipelineRunService.recordLog(
                    PipelineRunLog(
                        pipelineId = jobDef.pipelineId,
                        eventName = jobDef.eventName,
                        outcome = bosca.pipelines.model.PipelineRunStatus.FAILED,
                        startedAt = startedAt,
                        finishedAt = OffsetDateTime.now(),
                        durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                        errorMessage = e.message ?: e.toString(),
                    )
                )
            } catch (e2: Exception) {
                log.error("Failed to record pipeline run log for {}", jobDef.pipelineId, e2)
            }
        }
    }

    @OptIn(InternalDI::class)
    private suspend fun eventSerializer(eventName: String): KSerializer<*>? =
        ProviderRegistry.findAll(EventCatalogRegistrar::class)
            .filter { it.exists }
            .firstNotNullOfOrNull { it.get().serializers[eventName] }
}
