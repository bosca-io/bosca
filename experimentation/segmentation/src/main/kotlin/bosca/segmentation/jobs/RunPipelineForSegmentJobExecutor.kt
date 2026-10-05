package bosca.segmentation.jobs

import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.queue.annotations.JobDefinition
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.segmentation.configuration.JobQueueNames
import bosca.segmentation.model.SegmentPipelineInput
import bosca.segmentation.service.SegmentService
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private const val SEGMENT_PIPELINE_PAGE_SIZE = 1000
private const val SEGMENT_PIPELINE_EVENT_NAME = "segment-pipeline"
private val log = LoggerFactory.getLogger(RunPipelineForSegmentJobExecutor::class.java)

/**
 * Starts one durable pipeline run per member of a segment.
 *
 * The segment's audience is read in stable pages so an arbitrarily large segment is never held in
 * memory at once. Every body run receives `{ segmentId, profileId, context }` and is durable and
 * suspendable on its own rather than executed inline in this fan-out.
 *
 * A failure to start one body run is logged and skipped so one bad member cannot abort the rest of
 * the fan-out; cancellation always propagates.
 */
@JobDefinition(RunPipelineForSegmentJob::class, JobQueueNames.segmentationJobQueue, "run-pipeline-for-segment")
class RunPipelineForSegmentJobExecutor(
    private val segmentService: SegmentService,
    private val pipelineService: PipelineService,
    private val pipelineRunService: PipelineRunService,
    private val securityService: SecurityService,
) : AbstractJobExecutor<RunPipelineForSegmentJob>(RunPipelineForSegmentJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val authentication = securityService.impersonate(job.principalId)
        runPipelineForSegment(
            job = job,
            authentication = authentication,
            segmentService = segmentService,
            pipelineService = pipelineService,
            pipelineRunService = pipelineRunService,
        )
    }
}

/**
 * Fan a [RunPipelineForSegmentJob] out into one pipeline run per segment member.
 *
 * Pure of the job-queue harness (the payload and the resolved [authentication] are passed
 * in) so audience paging, context propagation, input shaping, and start-failure isolation are
 * unit-testable. [pageSize] is overridable for tests; production uses the default.
 */
internal suspend fun runPipelineForSegment(
    job: RunPipelineForSegmentJob,
    authentication: AuthenticationContext,
    segmentService: SegmentService,
    pipelineService: PipelineService,
    pipelineRunService: PipelineRunService,
    pageSize: Int = SEGMENT_PIPELINE_PAGE_SIZE,
) {
    val pipeline: Pipeline = pipelineService.get(job.pipelineId)
        ?: error("Pipeline not found: ${job.pipelineId}")
    val segmentIds = listOf(job.segmentId)

    var offset = 0L
    var started = 0L
    var failed = 0L
    while (true) {
        val profileIds = segmentService.getAudienceProfileIdsPaged(segmentIds, offset, pageSize)
        if (profileIds.isEmpty()) break
        for (profileId in profileIds) {
            try {
                pipelineRunService.start(
                    pipeline,
                    PipelineValue.of(
                        SegmentPipelineInput(
                            segmentId = job.segmentId,
                            profileId = profileId,
                            context = job.context,
                        ),
                        SegmentPipelineInput.serializer(),
                    ),
                    SEGMENT_PIPELINE_EVENT_NAME,
                    OffsetDateTime.now(),
                    authentication,
                )
                started++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                log.error(
                    "Failed to start pipeline {} for profile {} in segment {}",
                    job.pipelineId, profileId, job.segmentId, e,
                )
            }
        }
        offset += pageSize
    }

    log.info(
        "Segment {} fanned out over pipeline {}: started {} runs ({} failed to start)",
        job.segmentId, job.pipelineId, started, failed,
    )
}
