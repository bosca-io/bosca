package bosca.recommendations.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.service.RecommendationStrategyService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(EvaluateStrategyJob::class, JobQueueNames.recommendationsJobQueue, "evaluate-strategy")
class EvaluateStrategyJobExecutor : AbstractJobExecutor<EvaluateStrategyJob>(
    EvaluateStrategyJob.serializer()
) {
    override suspend fun execute() {
        val job = getJobDefinition()
        val strategyService: RecommendationStrategyService = provide()
        log.info("Evaluating strategy: ${job.strategyId}")
        val strategy = strategyService.evaluate(job.strategyId)
        log.info("Strategy ${job.strategyId} evaluated: ${strategy.name}")
    }

    companion object {
        private val log = LoggerFactory.getLogger(EvaluateStrategyJobExecutor::class.java)
    }
}
