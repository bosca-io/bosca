package bosca.git.jobs

import bosca.cache.requestCache
import bosca.di.provide
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory

@JobDefinition(ReindexAllJob::class, "git", "reindex-all")
class ReindexAllExecutor : AbstractJobExecutor<ReindexAllJob>(ReindexAllJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val searchService: SearchService = provide()
        val repoRepository: GitRepositoryRepository = provide()
        val refRepository: DfsRefRepository = provide()
        val json: Json = provide()

        val storage = job.storage
        if (storage != null) {
            storage.execute(searchService, repoRepository, refRepository, json)
        } else {
            val storageService: StorageSystemService = provide()
            storageService.getAll()
                .filter { it.type == StorageSystemType.SEARCH && it.name == STORAGE_SYSTEM_NAME }
                .forEach {
                    IndexStorageSystem(it.id, it.name)
                        .execute(searchService, repoRepository, refRepository, json)
                }
        }
    }

    private suspend fun IndexStorageSystem.execute(
        searchService: SearchService,
        repoRepository: GitRepositoryRepository,
        refRepository: DfsRefRepository,
        json: Json
    ) {
        val repoEnqueuer = provide<JobConfigurationEnqueuer>("repository-index")
        repoEnqueuer.enqueue(json.encodeToJsonElement(RepositoryIndexJob()))
        log.info("Enqueued bulk repository metadata reindex")

        // Drop every existing `_type=file` document before re-tagging. Document IDs
        // are content-addressed by (path, blobSha); any stale legacy docs (older
        // installer versions used a different key scheme and no `branches` overlay)
        // would otherwise linger and pollute query results.
        searchService.deleteByFilter(this, SearchFilter.eq("_type", "file"))
        log.info("Cleared existing file documents from {}", name)

        val fileEnqueuer = provide<JobConfigurationEnqueuer>("file-content-index")
        val repoIds = repoRepository.findActiveIds()
        var enqueuedJobs = 0
        var processedRepos = 0

        for (batch in repoIds.chunked(BATCH_SIZE)) {
            for (repoId in batch) {
                val repo = repoRepository.findById(repoId) ?: continue
                if (repo.deleted) continue

                // Enqueue an initial-index job per branch. The per-repo executor lock
                // serializes these so the multiple branches of one repo will not race
                // each other on shared `branches` overlay updates.
                val branchRefs = refRepository.findAll(repoId)
                    .filter { it.name.startsWith(BRANCH_REF_PREFIX) }
                for (ref in branchRefs) {
                    fileEnqueuer.enqueue(json.encodeToJsonElement(
                        FileContentIndexJob(
                            repositoryId = repoId,
                            ref = ref.name,
                            afterSha = ref.objectId,
                        )
                    ))
                    enqueuedJobs++
                }
                processedRepos++
            }
            requestCache().clearLocal()
            log.info("Enqueued {} file content reindex jobs across {} of {} repositories",
                enqueuedJobs, processedRepos, repoIds.size)
        }

        log.info("Reindex complete: enqueued {} file content index jobs", enqueuedJobs)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReindexAllExecutor::class.java)
        const val STORAGE_SYSTEM_NAME = "git-code"
        private const val BATCH_SIZE = 100
        private const val BRANCH_REF_PREFIX = "refs/heads/"
    }
}
