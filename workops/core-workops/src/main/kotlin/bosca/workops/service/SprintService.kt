package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.StartSprintInput

interface SprintService : Service {
    suspend fun listByBoard(boardId: UUID, offset: Long, limit: Int): List<Sprint>
    suspend fun getById(id: UUID): Sprint?
    suspend fun getByIds(ids: List<UUID>): List<Sprint>
    suspend fun create(input: CreateSprintInput): Sprint
    /** Starts the sprint, assigns selected tasks and returns unselected planned tasks to backlog atomically under the supplied actor. */
    suspend fun start(input: StartSprintInput, actingPrincipalId: UUID, actingProfileId: UUID?): Sprint
    /** Closes the sprint and applies unfinished-task destinations atomically, retaining its historical snapshot. */
    suspend fun close(input: CloseSprintInput, actingPrincipalId: UUID, actingProfileId: UUID?): Sprint

    /** Adds [taskId] to the appropriate collection for an open sprint; closed sprints reject new assignments. */
    suspend fun addTask(sprintId: UUID, taskId: UUID): Sprint?

    /** Removes [taskId] from an open sprint's collections, preserving closed historical snapshots. */
    suspend fun removeTask(sprintId: UUID, taskId: UUID): Sprint?
}
