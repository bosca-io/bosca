package bosca.git.jobs

import bosca.di.provide
import bosca.git.repository.GitRepositoryRepository
import bosca.git.service.RepositoryLifecycleService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(RepositoryBackupJob::class, "git", "repository-backup")
class RepositoryBackupExecutor : AbstractJobExecutor<RepositoryBackupJob>(RepositoryBackupJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val lifecycleService: RepositoryLifecycleService = provide()
        if (job.repositoryId != null) {
            backupRepository(lifecycleService, job.repositoryId)
        } else {
            val repoRepository: GitRepositoryRepository = provide()
            val repos = repoRepository.findActiveIds()
            log.info("Starting backup for {} active repositories", repos.size)
            for (repo in repos) {
                try {
                    backupRepository(lifecycleService, repo)
                } catch (e: Exception) {
                    log.error("Failed to backup repository {}", repo, e)
                }
            }
            log.info("Completed backup for {} repositories", repos.size)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RepositoryBackupExecutor::class.java)

        private suspend fun backupRepository(lifecycleService: RepositoryLifecycleService, repositoryId: UUID) {
            val path = lifecycleService.backup(repositoryId)
            log.info("Backed up repository {} to {}", repositoryId, path)
        }
    }
}
