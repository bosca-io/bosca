package bosca.experimentation.jobs

import bosca.experimentation.configuration.JobQueueNames
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/**
 * Job executor that triggers experiment result aggregation from the job queue.
 * Delegates to [aggregateExperimentResults] for the actual aggregation logic.
 */
@JobDefinition(ExperimentResultAggregationJob::class, JobQueueNames.experimentationJobQueue, "aggregate-results")
class ExperimentResultAggregationJobExecutor : AbstractJobExecutor<ExperimentResultAggregationJob>(
    ExperimentResultAggregationJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        aggregateExperimentResults(job.experimentId)
    }
}
