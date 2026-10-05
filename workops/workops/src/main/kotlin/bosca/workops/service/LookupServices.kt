package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.task.CreatePriorityInput
import bosca.workops.model.task.CreateResolutionInput
import bosca.workops.model.task.CreateTaskTypeInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.TaskTypeScheme
import bosca.workops.model.task.UpdatePriorityInput
import bosca.workops.model.task.UpdateResolutionInput
import bosca.workops.model.task.UpdateTaskTypeInput
import bosca.workops.model.workflow.CreateStatusInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.UpdateStatusInput
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ResolutionRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskTypeRepository
import bosca.workops.repository.TaskTypeSchemeRepository

@ServiceImplementation
class TaskTypeServiceImpl(private val repository: TaskTypeRepository) : TaskTypeService {
    override suspend fun list(): List<TaskType> = repository.getAll()
    override suspend fun getById(id: UUID): TaskType? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<TaskType> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreateTaskTypeInput): TaskType =
        repository.add(TaskType(name = input.name, description = input.description, iconKey = input.iconKey, colorHex = input.colorHex, hierarchyLevel = input.hierarchyLevel))

    override suspend fun update(id: UUID, input: UpdateTaskTypeInput): TaskType =
        repository.update(id, input.name, input.description, input.iconKey, input.colorHex, input.hierarchyLevel, input.expectedVersion)
            ?: error("TaskType $id not found or version mismatch")

    override suspend fun delete(id: UUID) = repository.deleteById(id)
}

@ServiceImplementation
class TaskTypeSchemeServiceImpl(private val repository: TaskTypeSchemeRepository) : TaskTypeSchemeService {
    override suspend fun list(): List<TaskTypeScheme> = repository.getAll()
    override suspend fun getById(id: UUID): TaskTypeScheme? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<TaskTypeScheme> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)
}

@ServiceImplementation
class StatusServiceImpl(private val repository: StatusRepository) : StatusService {
    override suspend fun list(): List<Status> = repository.getAll()
    override suspend fun getById(id: UUID): Status? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<Status> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreateStatusInput): Status =
        repository.add(Status(name = input.name, description = input.description, category = input.category, colorHex = input.colorHex))

    override suspend fun update(id: UUID, input: UpdateStatusInput): Status =
        repository.update(id, input.name, input.description, input.category, input.colorHex, input.expectedVersion)
            ?: error("Status $id not found or version mismatch")

    override suspend fun delete(id: UUID) = repository.deleteById(id)
}

@ServiceImplementation
class PriorityServiceImpl(private val repository: PriorityRepository) : PriorityService {
    override suspend fun list(): List<Priority> = repository.getAll()
    override suspend fun getById(id: UUID): Priority? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<Priority> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreatePriorityInput): Priority =
        repository.add(Priority(name = input.name, description = input.description, iconKey = input.iconKey, colorHex = input.colorHex, displayOrder = input.displayOrder))

    override suspend fun update(id: UUID, input: UpdatePriorityInput): Priority =
        repository.update(id, input.name, input.description, input.iconKey, input.colorHex, input.displayOrder, input.expectedVersion)
            ?: error("Priority $id not found or version mismatch")

    override suspend fun delete(id: UUID) = repository.deleteById(id)
}

@ServiceImplementation
class ResolutionServiceImpl(private val repository: ResolutionRepository) : ResolutionService {
    override suspend fun list(): List<Resolution> = repository.getAll()
    override suspend fun getById(id: UUID): Resolution? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<Resolution> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreateResolutionInput): Resolution =
        repository.add(Resolution(name = input.name, description = input.description, displayOrder = input.displayOrder))

    override suspend fun update(id: UUID, input: UpdateResolutionInput): Resolution =
        repository.update(id, input.name, input.description, input.displayOrder, input.expectedVersion)
            ?: error("Resolution $id not found or version mismatch")

    override suspend fun delete(id: UUID) = repository.deleteById(id)
}
