package bosca.workops.service

import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.SprintAlreadyActiveException
import bosca.workops.model.SprintCloseUnfinishedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.dispatch
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.SprintState
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.model.task.Task
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.SprintRepository

@ServiceImplementation
class SprintServiceImpl(
    private val repository: SprintRepository,
    private val boardService: BoardService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val taskServiceProvider: ObjectProvider<TaskService>,
    private val statusService: StatusService,
) : SprintService {

    override suspend fun listByBoard(boardId: UUID, offset: Long, limit: Int): List<Sprint> =
        repository.listByBoard(boardId, offset.coerceAtLeast(0), limit.coerceIn(1, 100))

    override suspend fun getById(id: UUID): Sprint? = repository.getById(id)

    override suspend fun getByIds(ids: List<UUID>): List<Sprint> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreateSprintInput): Sprint = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        repository.add(input.boardId, input.name, input.goal)
    }

    override suspend fun start(input: StartSprintInput, actingPrincipalId: UUID, actingProfileId: UUID?): Sprint = transaction {
        val sprint = repository.getById(input.sprintId)
            ?: throw WorkOpsNotFoundException("Sprint", input.sprintId.toString())
        // R8: at most one ACTIVE sprint per board.
        repository.findActive(sprint.boardId)?.let {
            if (it.id != sprint.id) throw SprintAlreadyActiveException(sprint.boardId)
        }
        val selectedIds = input.committedTaskIds.distinct()
        val tasks = activeTasks((sprint.committedTaskIds + selectedIds).distinct())
        selectedIds.firstOrNull { id -> tasks.none { it.id == id } }?.let { throw WorkOpsNotFoundException("Task", it.toString()) }
        val staged = sprint.copy(
            startDate = input.startDate ?: OffsetDateTime.now(),
            endDate = input.endDate,
            committedTaskIds = selectedIds,
            version = input.expectedVersion,
        )
        val started = repository.start(staged) ?: throw OptimisticLockFailedException("Sprint", input.sprintId)
        for (task in tasks) {
            when {
                task.id in selectedIds && task.sprintId != sprint.id -> taskServiceProvider.get().update(task.id,
                    UpdateTaskInput(sprintId = sprint.id, expectedVersion = task.version), actingPrincipalId, actingProfileId)
                task.id !in selectedIds && task.sprintId == sprint.id -> taskServiceProvider.get().update(task.id,
                    UpdateTaskInput(clearSprintId = true, expectedVersion = task.version), actingPrincipalId, actingProfileId)
            }
        }
        dispatchSprintNotification(started.boardId, NotificationEvent.SPRINT_STARTED)
        started
    }

    override suspend fun close(input: CloseSprintInput, actingPrincipalId: UUID, actingProfileId: UUID?): Sprint = transaction {
        val sprint = repository.getById(input.sprintId)
            ?: throw WorkOpsNotFoundException("Sprint", input.sprintId.toString())
        val knownIds = (sprint.committedTaskIds + sprint.addedDuringSprintTaskIds).toSet()
        val tasks = activeTasks(knownIds.toList())
        val statuses = statusService.getByIds(tasks.map { it.statusId }.distinct()).associateBy { it.id }
        val unfinished = tasks.filter { task ->
            requireNotNull(statuses[task.statusId]) { "Task status is unavailable." }.category !in setOf(StatusCategory.DONE, StatusCategory.CANCELLED)
        }
        if (input.destinations.map { it.taskId }.distinct().size != input.destinations.size || input.destinations.any { it.taskId !in knownIds }) {
            throw WorkOpsValidationException("destinations", "provide one destination per selected sprint task")
        }
        val provided = input.destinations.map { it.taskId }.toSet()
        val missing = unfinished.map { it.id }.toSet() - provided
        if (missing.isNotEmpty()) {
            throw SprintCloseUnfinishedException(sprint.id, missing.toList())
        }
        val destinations = input.destinations.associateBy { it.taskId }
        for (task in unfinished) {
            val targetId = destinations.getValue(task.id).targetSprintId ?: continue
            val target = repository.getById(targetId) ?: throw WorkOpsNotFoundException("Sprint", targetId.toString())
            if (target.id == sprint.id || target.state == SprintState.CLOSED) throw WorkOpsValidationException("destinations", "choose another open sprint or the backlog")
        }
        val closed = repository.close(input.sprintId, input.velocityPoints, input.expectedVersion)
            ?: throw OptimisticLockFailedException("Sprint", input.sprintId)
        for (task in unfinished) {
            val targetId = destinations.getValue(task.id).targetSprintId
            taskServiceProvider.get().update(task.id, UpdateTaskInput(sprintId = targetId,
                clearSprintId = targetId == null, expectedVersion = task.version), actingPrincipalId, actingProfileId)
        }
        dispatchSprintNotification(closed.boardId, NotificationEvent.SPRINT_CLOSED)
        closed
    }

    override suspend fun addTask(sprintId: UUID, taskId: UUID): Sprint? {
        while (true) {
            val sprint = repository.getById(sprintId) ?: return null
            if (sprint.state == SprintState.CLOSED) {
                throw WorkOpsValidationException("sprintId", "closed sprints cannot accept tasks")
            }
            if (taskId in sprint.committedTaskIds || taskId in sprint.addedDuringSprintTaskIds) return sprint
            val assigned = if (sprint.state == SprintState.FUTURE) {
                repository.addCommittedTask(sprint.id, taskId)
            } else {
                repository.addDuringSprintTask(sprint.id, taskId)
            }
            if (assigned != null) return assigned
            // A conditional write can lose a race with a state change or another assignment.
        }
    }

    override suspend fun removeTask(sprintId: UUID, taskId: UUID): Sprint? =
        repository.removeTask(sprintId, taskId)

    private suspend fun activeTasks(ids: List<UUID>): List<Task> =
        if (ids.isEmpty()) emptyList() else taskServiceProvider.get().getByIds(ids).filter { it.deletedAt == null }

    private suspend fun dispatchSprintNotification(boardId: UUID, event: NotificationEvent) {
        val board = boardService.getById(boardId) ?: return
        val boardProjectId = board.projectId
        val boardProgramId = board.programId
        val boardPortfolioId = board.portfolioId
        val projectIds = when {
            boardProjectId != null -> listOf(boardProjectId)
            boardProgramId != null -> projectsInProgram(boardProgramId).map { it.id }
            boardPortfolioId != null -> programsInPortfolio(boardPortfolioId)
                .flatMap { projectsInProgram(it.id) }
                .map { it.id }
            else -> boardService.listBoardProjects(board.id).map { it.projectId }
        }
        projectIds.distinct().forEach { projectId ->
            NotificationDeliveryRequested(
                NotificationDelivery(event = event, projectId = projectId),
            ).dispatch()
        }
    }

    private suspend fun projectsInProgram(programId: UUID): List<bosca.workops.model.project.Project> = buildList {
        var offset = 0L
        do {
            val page = projectService.listByProgram(programId, offset, PAGE_SIZE)
            addAll(page)
            offset += page.size
        } while (page.size == PAGE_SIZE)
    }

    private suspend fun programsInPortfolio(portfolioId: UUID): List<bosca.workops.model.project.Program> = buildList {
        var offset = 0L
        do {
            val page = programService.listByPortfolio(portfolioId, offset, PAGE_SIZE)
            addAll(page)
            offset += page.size
        } while (page.size == PAGE_SIZE)
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}
