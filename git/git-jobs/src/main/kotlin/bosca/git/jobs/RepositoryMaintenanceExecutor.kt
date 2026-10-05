package bosca.git.jobs

import bosca.di.provide
import bosca.git.repository.GitRepositoryRepository
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Enqueues [RepositoryGcJob]s for all active repositories. GC compacts
 * packfiles and removes orphaned packs left by failed or concurrent pushes.
 *
 * Designed to run on a weekly schedule via the scheduler. Each individual
 * GC job runs independently in the job queue with its own retry and
 * locking semantics.
 */
@JobDefinition(RepositoryMaintenanceJob::class, "git", "repository-maintenance")
class RepositoryMaintenanceExecutor : AbstractJobExecutor<RepositoryMaintenanceJob>(RepositoryMaintenanceJob.serializer()) {

    override suspend fun execute() {
        val repoRepository: GitRepositoryRepository = provide()
        val repos = repoRepository.findActiveIds()
        log.info("Scheduling GC for {} active repositories", repos.size)
        for (repo in repos) {
            RepositoryGcJob(repositoryId = repo).enqueue()
        }
        log.info("Enqueued {} GC jobs", repos.size)
    }

    companion object {
        private val log = LoggerFactory.getLogger(RepositoryMaintenanceExecutor::class.java)
    }
}
