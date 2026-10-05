package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.task.Task

interface CrossProjectMoveService : Service {
    /** R26 — moves the task into `target`, re-mints the key, audits the move. */
    suspend fun moveTaskToProject(
        taskId: UUID,
        input: MoveWorkOpsTaskInput,
        actingPrincipalId: UUID,
    ): Task
}
