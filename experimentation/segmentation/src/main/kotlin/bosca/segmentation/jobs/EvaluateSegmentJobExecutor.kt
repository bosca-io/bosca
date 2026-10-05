package bosca.segmentation.jobs

import bosca.di.provide
import bosca.segmentation.configuration.JobQueueNames
import bosca.segmentation.service.SegmentService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(EvaluateSegmentJob::class, JobQueueNames.segmentationJobQueue, "evaluate-segment")
class EvaluateSegmentJobExecutor : AbstractJobExecutor<EvaluateSegmentJob>(
    EvaluateSegmentJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        val segmentService: SegmentService = provide()
        log.info("Evaluating segment: ${job.segmentId}")
        val segment = segmentService.evaluate(job.segmentId)
        log.info("Segment ${job.segmentId} evaluated: ${segment.memberCount} members")
    }

    companion object {
        private val log = LoggerFactory.getLogger(EvaluateSegmentJobExecutor::class.java)
    }
}
