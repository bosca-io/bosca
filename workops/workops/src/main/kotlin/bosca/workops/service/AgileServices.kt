package bosca.workops.service

import bosca.db.transaction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.SprintAlreadyActiveException
import bosca.workops.model.SprintCloseUnfinishedException
import bosca.workops.model.VersionInUseException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardColumn
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.CreateBoardColumnInput
import bosca.workops.model.board.CreateBoardInput
import bosca.workops.model.board.UpdateBoardColumnInput
import bosca.workops.model.board.UpdateBoardInput
import bosca.workops.model.component.Component
import bosca.workops.model.component.CreateComponentInput
import bosca.workops.model.label.CreateLabelInput
import bosca.workops.model.label.Label
import bosca.workops.model.label.LabelScope
import bosca.workops.model.milestone.CreateMilestoneInput
import bosca.workops.model.milestone.Milestone
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.dispatch
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.SprintState
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.model.version.Version
import bosca.workops.repository.BoardRepository
import bosca.workops.repository.ComponentRepository
import bosca.workops.repository.LabelRepository
import bosca.workops.repository.MilestoneRepository
import bosca.workops.repository.SprintRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.VersionRepository

/*
 * Phase 5 services for the agile / release surface (R8 + R9). One
 * file per surface area would have meant six tiny files; collected
 * here for review density. Each service stays small — Phase 7's
 * permission scheme integration grows them when admin CRUD lands.
 */

// ----- Sprint -----

@ServiceImplementation
class SprintServiceImpl(
    private val repository: SprintRepository,
    private val boardService: BoardService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
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

    override suspend fun start(input: StartSprintInput): Sprint = transaction {
        val sprint = repository.getById(input.sprintId)
            ?: throw WorkOpsNotFoundException("Sprint", input.sprintId.toString())
        // R8: at most one ACTIVE sprint per board.
        repository.findActive(sprint.boardId)?.let {
            if (it.id != sprint.id) throw SprintAlreadyActiveException(sprint.boardId)
        }
        val staged = sprint.copy(
            startDate = input.startDate ?: OffsetDateTime.now(),
            endDate = input.endDate,
            committedTaskIds = input.committedTaskIds,
            version = input.expectedVersion,
        )
        val started = repository.start(staged) ?: throw OptimisticLockFailedException("Sprint", input.sprintId)
        dispatchSprintNotification(started.boardId, NotificationEvent.SPRINT_STARTED)
        started
    }

    override suspend fun close(input: CloseSprintInput): Sprint = transaction {
        val sprint = repository.getById(input.sprintId)
            ?: throw WorkOpsNotFoundException("Sprint", input.sprintId.toString())
        // R8: closing a sprint with unfinished tasks requires a
        // destination for each. Phase 7 / Phase 8 will introduce the
        // per-task resolution check that splits "unfinished" from
        // "finished"; for Phase 5 we trust the caller's destination
        // list matches the snapshot.
        val knownIds = (sprint.committedTaskIds + sprint.addedDuringSprintTaskIds).toSet()
        val provided = input.destinations.map { it.taskId }.toSet()
        val missing = knownIds - provided
        if (missing.isNotEmpty()) {
            throw SprintCloseUnfinishedException(sprint.id, missing.toList())
        }
        val closed = repository.close(input.sprintId, input.velocityPoints, input.expectedVersion)
            ?: throw OptimisticLockFailedException("Sprint", input.sprintId)
        dispatchSprintNotification(closed.boardId, NotificationEvent.SPRINT_CLOSED)
        closed
    }

    override suspend fun addTask(sprintId: UUID, taskId: UUID): Sprint? {
        val sprint = repository.getById(sprintId) ?: return null
        return when (sprint.state) {
            SprintState.FUTURE -> repository.addCommittedTask(sprint.id, taskId)
            SprintState.ACTIVE -> repository.addDuringSprintTask(sprint.id, taskId)
            SprintState.CLOSED -> sprint
        }
    }

    override suspend fun removeTask(sprintId: UUID, taskId: UUID): Sprint? =
        repository.removeTask(sprintId, taskId)

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

// ----- Board -----

@ServiceImplementation
class BoardServiceImpl(
    private val repository: BoardRepository,
    private val statusRepository: StatusRepository,
) : BoardService {

    override suspend fun getById(id: UUID): Board? = repository.getById(id)

    override suspend fun getByIds(ids: List<UUID>): List<Board> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun listByProject(projectId: UUID): List<Board> = repository.listByProject(projectId)

    override suspend fun listByProgram(programId: UUID): List<Board> = repository.listByProgram(programId)

    override suspend fun listByPortfolio(portfolioId: UUID): List<Board> = repository.listByPortfolio(portfolioId)

    override suspend fun listMultiProjectByProjectIds(projectIds: List<UUID>): List<Board> =
        if (projectIds.isEmpty()) emptyList() else repository.listMultiProjectByProjectIds(projectIds)

    override suspend fun listBoardProjects(boardId: UUID): List<BoardProject> =
        repository.listBoardProjects(boardId)

    override suspend fun create(input: CreateBoardInput): Board = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        val parentCount = listOfNotNull(input.projectId, input.programId, input.portfolioId).size
        val hasProjectIds = !input.projectIds.isNullOrEmpty()
        if (parentCount > 1) {
            throw WorkOpsValidationException(
                "scope",
                "at most one of projectId, programId, or portfolioId may be set",
            )
        }
        if (parentCount == 0 && !hasProjectIds) {
            throw WorkOpsValidationException(
                "scope",
                "either a parent (projectId, programId, portfolioId) or projectIds must be provided",
            )
        }
        if (input.projectId != null && hasProjectIds) {
            throw WorkOpsValidationException(
                "scope",
                "projectId and projectIds are mutually exclusive; use projectIds for multi-project boards",
            )
        }
        val board = repository.add(
            input.projectId, input.programId, input.portfolioId,
            input.name, input.type, input.swimlaneStrategy,
        )
        // Populate join table: single-project boards get one row; multi-project boards get N rows.
        val singleProjectId = input.projectId
        if (singleProjectId != null) {
            repository.addBoardProject(board.id, singleProjectId)
        }
        val multiProjectIds = input.projectIds
        if (!multiProjectIds.isNullOrEmpty()) {
            for (pid in multiProjectIds.distinct()) {
                repository.addBoardProject(board.id, pid)
            }
        }
        board
    }

    override suspend fun update(id: UUID, input: UpdateBoardInput): Board = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        val addProjectIds = input.addProjectIds
        if (addProjectIds != null) {
            for (projectId in addProjectIds.distinct()) {
                repository.addBoardProject(id, projectId)
            }
        }
        val removeProjectIds = input.removeProjectIds
        if (removeProjectIds != null) {
            for (projectId in removeProjectIds.distinct()) {
                repository.removeBoardProject(id, projectId)
            }
        }
        repository.update(id, input.name, input.type, input.swimlaneStrategy, input.expectedVersion)
            ?: throw OptimisticLockFailedException("Board", id)
    }

    override suspend fun addBoardProject(boardId: UUID, projectId: UUID): BoardProject? = transaction {
        repository.addBoardProject(boardId, projectId)
    }

    override suspend fun removeBoardProject(boardId: UUID, projectId: UUID) = transaction {
        repository.removeBoardProject(boardId, projectId)
    }

    override suspend fun addColumn(input: CreateBoardColumnInput): BoardColumn = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        if (input.statusIds.isEmpty()) {
            throw WorkOpsValidationException("statusIds", "a column must include at least one status")
        }
        // Confirm every statusId references a real seeded status row.
        val resolved = statusRepository.getByIds(input.statusIds)
        if (resolved.size != input.statusIds.distinct().size) {
            throw WorkOpsValidationException("statusIds", "one or more status ids are unknown")
        }
        repository.addColumn(
            BoardColumn(
                boardId = input.boardId,
                name = input.name,
                displayOrder = input.displayOrder,
                statusIds = input.statusIds,
                wipLimit = input.wipLimit,
            )
        )
    }

    override suspend fun updateColumn(input: UpdateBoardColumnInput): BoardColumn = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        if (input.statusIds.isEmpty()) {
            throw WorkOpsValidationException("statusIds", "a column must include at least one status")
        }
        val resolved = statusRepository.getByIds(input.statusIds)
        if (resolved.size != input.statusIds.distinct().size) {
            throw WorkOpsValidationException("statusIds", "one or more status ids are unknown")
        }
        repository.updateColumn(
            BoardColumn(
                id = input.id,
                boardId = UUID.NIL,
                name = input.name,
                displayOrder = input.displayOrder,
                statusIds = input.statusIds,
                wipLimit = input.wipLimit,
            )
        ) ?: throw WorkOpsNotFoundException("BoardColumn", input.id.toString())
    }

    override suspend fun getColumnById(columnId: UUID): BoardColumn? = repository.getColumnById(columnId)

    override suspend fun listColumns(boardId: UUID): List<BoardColumn> = repository.listColumns(boardId)

    override suspend fun deleteColumn(columnId: UUID) = repository.deleteColumn(columnId)

    override suspend fun delete(boardId: UUID) = repository.deleteById(boardId)
}

// ----- Version -----

@ServiceImplementation
class VersionServiceImpl(
    private val repository: VersionRepository,
) : VersionService {

    override suspend fun getById(id: UUID): Version? = repository.getById(id)

    override suspend fun getByIds(ids: List<UUID>): List<Version> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun listByProject(projectId: UUID): List<Version> = repository.listByProject(projectId)

    override suspend fun create(input: CreateVersionInput): Version = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        val seq = repository.maxSequenceNumber(input.projectId).toInt() + 1
        repository.add(
            projectId = input.projectId,
            name = input.name,
            description = input.description,
            startDate = input.startDate,
            releaseDate = input.releaseDate,
            sequenceNumber = seq,
        )
    }

    override suspend fun release(id: UUID, expectedVersion: Long): Version = transaction {
        val existing = repository.getById(id)
            ?: throw WorkOpsNotFoundException("Version", id.toString())
        repository.update(
            id = id,
            name = existing.name,
            description = existing.description,
            startDate = existing.startDate,
            releaseDate = existing.releaseDate ?: OffsetDateTime.now(),
            released = true,
            archived = existing.archived,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Version", id)
    }

    override suspend fun delete(id: UUID) = transaction {
        if (repository.isReferencedByTasks(id)) {
            throw VersionInUseException(id)
        }
        repository.deleteById(id)
    }
}

// ----- Component / Label / Milestone -----

@ServiceImplementation
class ComponentServiceImpl(
    private val repository: ComponentRepository,
) : ComponentService {
    override suspend fun listByProject(projectId: UUID): List<Component> = repository.listByProject(projectId)
    override suspend fun getById(id: UUID): Component? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<Component> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreateComponentInput): Component = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        repository.add(
            projectId = input.projectId,
            name = input.name,
            description = input.description,
            defaultAssigneeProfileId = input.defaultAssigneeProfileId,
            leadProfileId = input.leadProfileId,
            assigneeMode = input.assigneeMode,
        )
    }

    override suspend fun delete(id: UUID) = repository.deleteById(id)
}

@ServiceImplementation
class LabelServiceImpl(
    private val repository: LabelRepository,
) : LabelService {
    private val kebabPattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    override suspend fun getById(id: UUID): Label? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<Label> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun listGlobal(): List<Label> = repository.listGlobal()
    override suspend fun listByPortfolio(portfolioId: UUID): List<Label> = repository.listByPortfolio(portfolioId)
    override suspend fun listByProgram(programId: UUID): List<Label> = repository.listByProgram(programId)
    override suspend fun listByProject(projectId: UUID): List<Label> = repository.listByProject(projectId)

    override suspend fun create(input: CreateLabelInput): Label = transaction {
        val normalized = input.name.lowercase()
        if (!kebabPattern.matches(normalized)) {
            throw WorkOpsValidationException("name", "must be lowercase kebab-case; got '${input.name}'")
        }
        // R9 acceptance: scope ↔ parent id consistency.
        val parentMissing = when (input.scope) {
            LabelScope.GLOBAL -> input.portfolioId != null || input.programId != null || input.projectId != null
            LabelScope.PORTFOLIO -> input.portfolioId == null
            LabelScope.PROGRAM -> input.programId == null
            LabelScope.PROJECT -> input.projectId == null
        }
        if (parentMissing) {
            throw WorkOpsValidationException("scope", "${input.scope} requires the matching parent id")
        }
        repository.add(
            name = normalized,
            colorHex = input.colorHex,
            scope = input.scope,
            portfolioId = input.portfolioId,
            programId = input.programId,
            projectId = input.projectId,
        )
    }

    override suspend fun update(id: UUID, name: String, colorHex: String?, expectedVersion: Long): Label = transaction {
        val normalized = name.lowercase()
        if (!kebabPattern.matches(normalized)) {
            throw WorkOpsValidationException("name", "must be lowercase kebab-case; got '$name'")
        }
        repository.update(id, normalized, colorHex, expectedVersion)
            ?: throw OptimisticLockFailedException("Label", id)
    }

    override suspend fun delete(id: UUID) = repository.deleteById(id)
}

@ServiceImplementation
class MilestoneServiceImpl(
    private val repository: MilestoneRepository,
) : MilestoneService {
    override suspend fun listByProgram(programId: UUID): List<Milestone> = repository.listByProgram(programId)
    override suspend fun getById(id: UUID): Milestone? = repository.getById(id)
    override suspend fun getByIds(ids: List<UUID>): List<Milestone> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: CreateMilestoneInput): Milestone = transaction {
        if (input.name.isBlank()) throw WorkOpsValidationException("name", "required")
        repository.add(input.programId, input.name, input.description, input.targetDate)
    }

    override suspend fun close(id: UUID, expectedVersion: Long): Milestone = transaction {
        repository.close(id, expectedVersion)
            ?: throw OptimisticLockFailedException("Milestone", id)
    }
}
