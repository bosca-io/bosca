package bosca.workops.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.serialization.UUID
import bosca.workops.model.search.SpecSearchDocument
import bosca.workops.repository.StatusRepository
import bosca.workops.service.ProjectService
import bosca.workops.service.SpecService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory

@JobDefinition(SpecIndexJob::class, "workops", "spec-index")
class SpecIndexExecutor : AbstractJobExecutor<SpecIndexJob>(SpecIndexJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val specId = job.specId ?: return
        val searchService: SearchService = provide()
        val storageService: StorageSystemService = provide()
        val systems = storageService.getAll()
            .filter { it.type == StorageSystemType.SEARCH && it.name.isNotBlank() }
            .map { IndexStorageSystem(it.id, it.name) }
        for (system in systems) {
            if (job.deleteOnly) {
                searchService.deleteByFilter(system, SearchFilter.eq("id", specId.toString()))
            } else {
                index(system, specId, searchService)
            }
        }
    }

    private suspend fun index(
        system: IndexStorageSystem,
        specId: UUID,
        searchService: SearchService,
    ) {
        val specService: SpecService = provide()
        val spec = specService.getById(specId)
        if (spec == null || spec.deletedAt != null) {
            searchService.deleteByFilter(system, SearchFilter.eq("id", specId.toString()))
            return
        }
        val projectService: ProjectService = provide()
        val statusRepository: StatusRepository = provide()
        val project = spec.projectId?.let { projectService.getById(it) }
        val status = statusRepository.getById(spec.statusId)
        val doc = SpecSearchDocument(
            id = spec.id.toString(),
            key = spec.key,
            metadataId = spec.metadataId,
            projectId = spec.projectId,
            projectKey = project?.key,
            programId = spec.programId,
            ownerProfileId = spec.ownerProfileId,
            statusId = spec.statusId,
            statusName = status?.name ?: "",
            statusCategory = status?.category?.name ?: "TODO",
            parentSpecId = spec.parentSpecId,
            labelIds = spec.labelIds.map { it.toString() },
            watcherProfileIds = spec.watcherProfileIds.map { it.toString() },
            createdAt = spec.createdAt,
            modifiedAt = spec.modifiedAt,
            deleted = false,
        )
        val json: Json = provide()
        searchService.index(system, json.encodeToJsonElement(doc))
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpecIndexExecutor::class.java)
    }
}
