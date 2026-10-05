package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.repository.TaskAffectedProjectRepository

@ServiceImplementation
class TaskAffectedProjectServiceImpl(
    private val repository: TaskAffectedProjectRepository,
) : TaskAffectedProjectService {
    override suspend fun listForTask(taskId: UUID) = repository.listForTask(taskId)
    override suspend fun add(taskId: UUID, projectId: UUID) = repository.add(taskId, projectId)
    override suspend fun remove(taskId: UUID, projectId: UUID) = repository.remove(taskId, projectId)
}
