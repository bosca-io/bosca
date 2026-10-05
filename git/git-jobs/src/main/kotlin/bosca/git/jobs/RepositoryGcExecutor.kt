package bosca.git.jobs

import bosca.di.provide
import bosca.git.service.RepositoryLifecycleService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Executes garbage collection on a single repository's DFS object store,
 * compacting loose objects into packfiles and updating disk-size metrics.
 */
@JobDefinition(RepositoryGcJob::class, "git", "repository-gc")
class RepositoryGcExecutor : AbstractJobExecutor<RepositoryGcJob>(RepositoryGcJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        runGc(job, provide())
    }

    companion object {
        private val log = LoggerFactory.getLogger(RepositoryGcExecutor::class.java)

        /**
         * How long to delay a GC job whose repository write lock was held by
         * another writer. Long enough for any realistic push to finish, short
         * enough that a busy repository still gets compacted the same hour
         * instead of waiting for the next weekly maintenance sweep.
         */
        internal val LOCK_BUSY_RETRY_DELAY: Duration = 5.minutes

        suspend fun runGc(job: RepositoryGcJob, lifecycleService: RepositoryLifecycleService) {
            log.info("Running GC on repository {}", job.repositoryId)
            if (!lifecycleService.runGc(job.repositoryId)) {
                log.info(
                    "GC on repository {} skipped: write lock busy; retrying in {}",
                    job.repositoryId, LOCK_BUSY_RETRY_DELAY
                )
                throw DelayException(LOCK_BUSY_RETRY_DELAY)
            }
            log.info("GC completed for repository {}", job.repositoryId)
        }
    }
}
