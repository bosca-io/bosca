package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
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

/*
 * CRUD services for the workops lookup tables (task types, statuses,
 * priorities, resolutions). Read paths support GraphQL field resolution
 * on tasks; create / update / delete paths back the admin mutation
 * controllers in [bosca.workops.controller.LookupMutationControllers].
 */

interface TaskTypeService : Service {
    suspend fun list(): List<TaskType>
    suspend fun getById(id: UUID): TaskType?
    suspend fun getByIds(ids: List<UUID>): List<TaskType>
    suspend fun create(input: CreateTaskTypeInput): TaskType
    suspend fun update(id: UUID, input: UpdateTaskTypeInput): TaskType
    suspend fun delete(id: UUID)
}

interface TaskTypeSchemeService : Service {
    suspend fun list(): List<TaskTypeScheme>
    suspend fun getById(id: UUID): TaskTypeScheme?
    suspend fun getByIds(ids: List<UUID>): List<TaskTypeScheme>
}

interface StatusService : Service {
    suspend fun list(): List<Status>
    suspend fun getById(id: UUID): Status?
    suspend fun getByIds(ids: List<UUID>): List<Status>
    suspend fun create(input: CreateStatusInput): Status
    suspend fun update(id: UUID, input: UpdateStatusInput): Status
    suspend fun delete(id: UUID)
}

interface PriorityService : Service {
    suspend fun list(): List<Priority>
    suspend fun getById(id: UUID): Priority?
    suspend fun getByIds(ids: List<UUID>): List<Priority>
    suspend fun create(input: CreatePriorityInput): Priority
    suspend fun update(id: UUID, input: UpdatePriorityInput): Priority
    suspend fun delete(id: UUID)
}

interface ResolutionService : Service {
    suspend fun list(): List<Resolution>
    suspend fun getById(id: UUID): Resolution?
    suspend fun getByIds(ids: List<UUID>): List<Resolution>
    suspend fun create(input: CreateResolutionInput): Resolution
    suspend fun update(id: UUID, input: UpdateResolutionInput): Resolution
    suspend fun delete(id: UUID)
}
