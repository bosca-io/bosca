package bosca.git.jobs

import bosca.di.provide
import bosca.git.service.RepositoryLifecycleService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Physically deletes the object-storage files for packs that GC and thin-pack
 * compaction soft-deleted, once each pack has aged past its reap grace window.
 * This is the deferred half of the atomic pack swap: the swap only marks replaced
 * packs `deleted_at`, and this job reclaims their storage later — never while a
 * concurrent reader might still reference them.
 *
 * Runs on an hourly schedule via the scheduler.
 */
@JobDefinition(DfsPackReapJob::class, "git", "dfs-pack-reap")
class DfsPackReapExecutor : AbstractJobExecutor<DfsPackReapJob>(DfsPackReapJob.serializer()) {

    override suspend fun execute() {
        val lifecycleService: RepositoryLifecycleService = provide()
        log.info("Reaping soft-deleted DFS packs")
        lifecycleService.reapDeletedPacks()
        log.info("DFS pack reap completed")
    }

    companion object {
        private val log = LoggerFactory.getLogger(DfsPackReapExecutor::class.java)
    }
}
