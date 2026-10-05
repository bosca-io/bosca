package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.release.TaskAffectedProject

interface TaskAffectedProjectService : Service {
    suspend fun listForTask(taskId: UUID): List<TaskAffectedProject>
    suspend fun add(taskId: UUID, projectId: UUID): TaskAffectedProject?
    suspend fun remove(taskId: UUID, projectId: UUID)
}
