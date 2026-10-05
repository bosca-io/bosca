package bosca.git.jobs

import bosca.cache.requestCache
import bosca.di.provide
import bosca.git.repository.GitRepositoryRepository
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

@JobDefinition(RepositoryIndexJob::class, "git", "repository-index")
class RepositoryIndexExecutor : AbstractJobExecutor<RepositoryIndexJob>(RepositoryIndexJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val searchService: SearchService = provide()
        val repoRepository: GitRepositoryRepository = provide()
        val storage = job.storage
        if (storage != null) {
            storage.execute(searchService, repoRepository, job)
        } else {
            val storageService: StorageSystemService = provide()
            storageService.getAll()
                .filter { it.type == StorageSystemType.SEARCH && it.name == STORAGE_SYSTEM_NAME }
                .forEach {
                    IndexStorageSystem(it.id, it.name).execute(searchService, repoRepository, job)
                }
        }
    }

    private suspend fun IndexStorageSystem.execute(
        searchService: SearchService,
        repoRepository: GitRepositoryRepository,
        job: RepositoryIndexJob
    ) {
        val repositoryId = job.repositoryId
        if (repositoryId != null) {
            if (job.deleteOnly) {
                searchService.delete(this, repositoryId.toString())
            } else {
                val repo = repoRepository.findById(repositoryId)
                if (repo == null || repo.deleted) {
                    searchService.delete(this, repositoryId.toString())
                } else {
                    searchService.index(this, repo.toSearchDocument())
                }
            }
        } else if (!job.deleteOnly) {
            var offset = 0
            val batchSize = 100
            val repos = repoRepository.findActiveIds()
            for (batch in repos.chunked(batchSize)) {
                val documents = batch.mapNotNull { stub ->
                    repoRepository.findById(stub)?.takeIf { !it.deleted }?.toSearchDocument()
                }
                if (documents.isNotEmpty()) {
                    searchService.index(this, documents)
                }
                offset += batchSize
                log.info("Indexed {} repositories", offset)
                requestCache().clearLocal()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RepositoryIndexExecutor::class.java)

        const val STORAGE_SYSTEM_NAME = "git-code"

        private fun bosca.git.model.Repository.toSearchDocument(): JsonElement = buildJsonObject {
            put("id", id.toString())
            put("_type", "repository")
            put("name", name)
            put("slug", slug)
            put("description", description ?: "")
            put("ownerId", ownerId.toString())
            put("visibility", visibility.name)
            put("defaultBranch", defaultBranch)
            val ct = contentType
            put("contentType", if (ct != null) ct.name else "")
            put("archived", archived)
            put("diskSizeBytes", diskSizeBytes)
            put("created", created.toString())
            put("updated", updated.toString())
        }
    }
}
