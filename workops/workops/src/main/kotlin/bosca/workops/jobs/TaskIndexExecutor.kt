package bosca.workops.jobs

import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.workops.model.search.TaskSearchDocument
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ResolutionRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskRepository
import bosca.workops.repository.TaskTypeRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory

@JobDefinition(TaskIndexJob::class, "workops", "task-index")
class TaskIndexExecutor : AbstractJobExecutor<TaskIndexJob>(TaskIndexJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val taskId = job.taskId ?: return
        val searchService: SearchService = provide()
        val storageService: StorageSystemService = provide()
        val systems = storageService.getAll()
            .filter { it.type == StorageSystemType.SEARCH && it.name.isNotBlank() }
            .map { IndexStorageSystem(it.id, it.name) }
        for (system in systems) {
            if (job.deleteOnly) {
                searchService.deleteByFilter(system, SearchFilter.eq("id", taskId.toString()))
            } else {
                index(system, taskId, searchService)
            }
        }
    }

    private suspend fun index(
        system: IndexStorageSystem,
        taskId: UUID,
        searchService: SearchService,
    ) {
        val taskRepository: TaskRepository = provide()
        val task = taskRepository.getById(taskId)
        if (task == null || task.deletedAt != null) {
            searchService.deleteByFilter(system, SearchFilter.eq("id", taskId.toString()))
            return
        }
        val projectRepository: ProjectRepository = provide()
        val taskTypeRepository: TaskTypeRepository = provide()
        val statusRepository: StatusRepository = provide()
        val priorityRepository: PriorityRepository = provide()
        val resolutionRepository: ResolutionRepository = provide()
        val project = projectRepository.getById(task.projectId)
        val taskType = taskTypeRepository.getById(task.taskTypeId)
        val status = statusRepository.getById(task.statusId)
        val priority = priorityRepository.getById(task.priorityId)
        val resolution = task.resolutionId?.let { resolutionRepository.getById(it) }
        if (project == null) log.warn("Task {} references missing project {}", task.key, task.projectId)
        if (taskType == null) log.warn("Task {} references missing task type {}", task.key, task.taskTypeId)
        if (status == null) log.warn("Task {} references missing status {}", task.key, task.statusId)
        if (priority == null) log.warn("Task {} references missing priority {}", task.key, task.priorityId)
        val doc = TaskSearchDocument(
            id = task.id.toString(),
            key = task.key,
            summary = task.summary,
            descriptionPlain = task.descriptionMarkdown,
            projectId = task.projectId,
            projectKey = if (project == null) "" else project.key,
            taskTypeId = task.taskTypeId,
            taskTypeName = if (taskType == null) "" else taskType.name,
            statusId = task.statusId,
            statusName = if (status == null) "" else status.name,
            statusCategory = if (status == null) "TODO" else status.category.name,
            priorityId = task.priorityId,
            priorityName = if (priority == null) "" else priority.name,
            resolutionId = task.resolutionId,
            resolutionName = resolution?.name,
            assigneeProfileId = task.assigneeProfileId,
            reporterProfileId = task.reporterProfileId,
            labelIds = task.labelIds.map { it.toString() },
            componentIds = task.componentIds.map { it.toString() },
            watcherProfileIds = task.watcherProfileIds.map { it.toString() },
            sprintId = task.sprintId,
            createdAt = task.createdAt,
            modifiedAt = task.modifiedAt,
            dueDate = task.dueDate,
            deleted = false,
        )
        val json: Json = provide()
        searchService.index(system, json.encodeToJsonElement(doc))
    }

    companion object {
        private val log = LoggerFactory.getLogger(TaskIndexExecutor::class.java)
    }
}
