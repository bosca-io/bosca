package bosca.git.jobs

import bosca.di.provide
import bosca.git.model.dispatch
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.RepositoryContentType
import bosca.git.model.ScriptSourceUpdatedEvent
import bosca.git.repository.GitRepositoryRepository
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.slf4j.LoggerFactory

/**
 * Scans changed files after a push to SCRIPT_PROJECT repositories
 * and emits events so the scripting engine can reload affected sources.
 */
@JobDefinition(ContentChangeWatcherJob::class, "git", "content-change-watcher")
class ContentChangeWatcherExecutor : AbstractJobExecutor<ContentChangeWatcherJob>(ContentChangeWatcherJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        watchChanges(job, provide(), provide())
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContentChangeWatcherExecutor::class.java)
        private val SCRIPT_PATH_REGEX = Regex("""scripts/([^/]+)/source\.\w+""")

        suspend fun watchChanges(
            job: ContentChangeWatcherJob,
            repoRepository: GitRepositoryRepository,
            dfsManager: BoscaDfsRepositoryManager
        ) {
            val repository = repoRepository.findById(job.repositoryId) ?: return
            val contentType = repository.contentType ?: return

            if (contentType != RepositoryContentType.SCRIPT_PROJECT) return

            val changedFiles = withContext(GitWorkDispatcher) { computeChangedFiles(dfsManager, job) }
            if (changedFiles.isEmpty()) return

            for (file in changedFiles) {
                val match = SCRIPT_PATH_REGEX.find(file)
                if (match != null) {
                    ScriptSourceUpdatedEvent(
                        repositoryId = job.repositoryId,
                        ref = job.ref,
                        scriptKey = match.groupValues[1],
                        path = file
                    ).dispatch()
                }
            }

            log.info("Content change watcher processed {} files for repository {}", changedFiles.size, job.repositoryId)
        }

        private fun computeChangedFiles(
            dfsManager: BoscaDfsRepositoryManager,
            job: ContentChangeWatcherJob
        ): List<String> {
            if (job.beforeSha == ObjectId.zeroId().name()) return emptyList()

            val dfsRepo = dfsManager.open(job.repositoryId)
            return dfsRepo.use { repo ->
                val revWalk = RevWalk(repo)
                try {
                    val oldCommit = revWalk.parseCommit(ObjectId.fromString(job.beforeSha))
                    val newCommit = revWalk.parseCommit(ObjectId.fromString(job.afterSha))

                    val formatter = DiffFormatter(DisabledOutputStream.INSTANCE)
                    formatter.setRepository(repo)
                    val entries = formatter.scan(oldCommit.tree, newCommit.tree)
                    entries.map { it.newPath ?: it.oldPath }
                } finally {
                    revWalk.dispose()
                }
            }
        }
    }
}
