package bosca.git.jobs

import bosca.di.provide
import bosca.git.service.RepositoryLifecycleService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Permanently deletes repositories that have been soft-deleted for more than
 * 30 days. Removes all DFS pack data from object storage, refs from the
 * database, and the repository row itself.
 */
@JobDefinition(RepositoryPurgeJob::class, "git", "repository-purge")
class RepositoryPurgeExecutor : AbstractJobExecutor<RepositoryPurgeJob>(RepositoryPurgeJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        runPurge(job, provide())
    }

    companion object {
        private val log = LoggerFactory.getLogger(RepositoryPurgeExecutor::class.java)

        suspend fun runPurge(job: RepositoryPurgeJob, lifecycleService: RepositoryLifecycleService) {
            log.info("Running purge for expired soft-deleted repositories")
            lifecycleService.purgeExpiredRepositories()
            log.info("Purge completed")
        }
    }
}
