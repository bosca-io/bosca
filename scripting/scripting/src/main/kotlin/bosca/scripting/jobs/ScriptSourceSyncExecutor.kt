package bosca.scripting.jobs

import bosca.git.service.ScriptSourceUpdate
import bosca.git.service.SourceRefSyncService
import bosca.queue.annotations.JobDefinition
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.repository.ScriptRepository
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Background executor for [ScriptSourceSyncJob]. Reads the affected
 * scripts at the new commit via [SourceRefSyncService.findAffectedScripts]
 * and writes the new source into each one. The JobQueue's distributed
 * lock serializes execution per job, so duplicate enqueues from multiple
 * PubSub subscribers are at worst wasted work.
 */
@JobDefinition(
    definition = ScriptSourceSyncJob::class,
    queue = JobQueueNames.scriptingJobQueue,
    name = "script-source-sync",
)
class ScriptSourceSyncExecutor(
    private val sourceRefSyncService: SourceRefSyncService,
    private val scriptRepository: ScriptRepository,
) : AbstractJobExecutor<ScriptSourceSyncJob>(ScriptSourceSyncJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val updates = sourceRefSyncService.findAffectedScripts(
            repositoryId = job.repositoryId,
            pushedRef = job.ref,
            beforeSha = job.beforeSha,
            afterSha = job.afterSha,
        )
        for (update in updates) {
            apply(update)
        }
    }

    private suspend fun apply(update: ScriptSourceUpdate) {
        val existing = scriptRepository.getById(update.scriptId)
        if (existing == null) {
            log.warn("Script {} referenced by source ref no longer exists; skipping", update.scriptId)
            return
        }
        if (existing.source == update.newSource) return
        val saved = scriptRepository.update(existing.copy(source = update.newSource))
        if (saved == null) {
            log.warn("Script {} update failed (optimistic lock); skipping", update.scriptId)
            return
        }
        log.info("Script {} source updated from git at {}", update.scriptId, update.commitSha)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScriptSourceSyncExecutor::class.java)
    }
}
