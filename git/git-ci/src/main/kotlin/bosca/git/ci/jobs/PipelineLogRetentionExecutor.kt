package bosca.git.ci.jobs

import bosca.di.provide
import bosca.git.model.PipelineLogRetentionJob
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(PipelineLogRetentionJob::class, "git", "pipeline-log-retention")
class PipelineLogRetentionExecutor : AbstractJobExecutor<PipelineLogRetentionJob>(PipelineLogRetentionJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val pipelineService = provide<PipelineService>()
        val logService = provide<PipelineLogService>()

        val repositoryIds = pipelineService.all().map { it.repositoryId }.toSet()
        var cleaned = 0

        for (repositoryId in repositoryIds) {
            try {
                logService.deleteExpiredLogs(repositoryId, job.retentionDays)
                cleaned++
            } catch (e: Exception) {
                log.warn("Failed to clean logs for repository {}: {}", repositoryId, e.message)
            }
        }

        log.info("Pipeline log retention completed for {} repositories (retention: {} days)", cleaned, job.retentionDays)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineLogRetentionExecutor::class.java)
    }
}
