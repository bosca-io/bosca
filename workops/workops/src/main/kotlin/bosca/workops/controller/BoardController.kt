package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardColumn
import bosca.workops.model.board.CreateBoardColumnInput
import bosca.workops.model.board.CreateBoardInput
import bosca.workops.model.board.UpdateBoardColumnInput
import bosca.workops.model.board.UpdateBoardInput
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.workflow.Status
import bosca.workops.service.BoardService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.SprintService
import bosca.workops.service.StatusService

@TypeController(type = "WorkOpsBoard")
class BoardTypeController(
    private val boardService: BoardService,
    private val sprintService: SprintService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
) : GraphQLController<Board> {

    @Field fun id(b: Board) = b.id
    @Field fun projectId(b: Board) = b.projectId
    @Field fun programId(b: Board) = b.programId
    @Field fun portfolioId(b: Board) = b.portfolioId
    @Field fun name(b: Board) = b.name
    @Field fun type(b: Board) = b.type
    @Field fun filterId(b: Board) = b.filterId
    @Field fun subFilterId(b: Board) = b.subFilterId
    @Field fun swimlaneStrategy(b: Board) = b.swimlaneStrategy
    @Field fun createdAt(b: Board) = b.createdAt
    @Field fun modifiedAt(b: Board) = b.modifiedAt
    @Field fun version(b: Board) = b.version

    @Field
    suspend fun project(board: Board): Project? =
        board.projectId?.let { projectService.getById(it) }

    @Field
    suspend fun program(board: Board): Program? =
        board.programId?.let { programService.getById(it) }

    @Field
    suspend fun portfolio(board: Board): Portfolio? =
        board.portfolioId?.let { portfolioService.getById(it) }

    @Field
    suspend fun projects(board: Board): List<Project> {
        val boardProjects = boardService.listBoardProjects(board.id)
        if (boardProjects.isEmpty()) return emptyList()
        return projectService.getByIds(boardProjects.map { it.projectId })
    }

    @Field
    suspend fun columns(board: Board): List<BoardColumn> = boardService.listColumns(board.id)

    @Field
    suspend fun sprints(board: Board, offset: Long, limit: Int): List<Sprint> =
        sprintService.listByBoard(board.id, offset, limit)
}

@TypeController(type = "WorkOpsBoardColumn")
class BoardColumnTypeController(
    private val statusService: StatusService,
) : GraphQLController<BoardColumn> {

    @Field fun id(c: BoardColumn) = c.id
    @Field fun boardId(c: BoardColumn) = c.boardId
    @Field fun name(c: BoardColumn) = c.name
    @Field fun displayOrder(c: BoardColumn) = c.displayOrder
    @Field fun statusIds(c: BoardColumn) = c.statusIds
    @Field fun wipLimit(c: BoardColumn) = c.wipLimit

    @Field
    suspend fun statuses(column: BoardColumn): List<Status> =
        statusService.getByIds(column.statusIds)
}

object WorkOpsBoards

@TypeController
class BoardQueryController(
    private val service: BoardService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
) : GraphQLController<WorkOpsBoards> {

    @Field
    suspend fun board(authentication: AuthenticationContext, id: UUID): Board? {
        val board = service.getById(id) ?: return null
        verifyViewOnBoard(authentication, board)
        return board
    }

    @Field
    suspend fun byProject(authentication: AuthenticationContext, projectId: UUID): List<Board> {
        val project = projectService.getById(projectId)
            ?: error("Project $projectId not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        return service.listByProject(projectId)
    }

    @Field
    suspend fun byProgram(authentication: AuthenticationContext, programId: UUID): List<Board> {
        val program = programService.getById(programId)
            ?: error("Program $programId not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        return service.listByProgram(programId)
    }

    @Field
    suspend fun byPortfolio(authentication: AuthenticationContext, portfolioId: UUID): List<Board> {
        val portfolio = portfolioService.getById(portfolioId)
            ?: error("Portfolio $portfolioId not found")
        portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.VIEW)
        return service.listByPortfolio(portfolioId)
    }

    @Field
    suspend fun byProjects(authentication: AuthenticationContext, projectIds: List<UUID>): List<Board> {
        for (pid in projectIds) {
            val project = projectService.getById(pid)
                ?: error("Project $pid not found")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        }
        return service.listMultiProjectByProjectIds(projectIds)
    }

    /**
     * Verifies VIEW permission on a board. For single-parent boards,
     * checks the parent. For multi-project boards (no single parent),
     * requires VIEW on at least one constituent project.
     */
    private suspend fun verifyViewOnBoard(authentication: AuthenticationContext, board: Board) {
        board.projectId?.let { pid ->
            val project = projectService.getById(pid)
                ?: error("Project $pid not found for board ${board.id}")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
            return
        }
        board.programId?.let { pid ->
            val program = programService.getById(pid)
                ?: error("Program $pid not found for board ${board.id}")
            programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
            return
        }
        board.portfolioId?.let { pid ->
            val portfolio = portfolioService.getById(pid)
                ?: error("Portfolio $pid not found for board ${board.id}")
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.VIEW)
            return
        }
        // Multi-project board: require VIEW on at least one constituent project.
        val boardProjects = service.listBoardProjects(board.id)
        if (boardProjects.isEmpty()) error("Board ${board.id} has no parent and no projects")
        for (bp in boardProjects) {
            val project = projectService.getById(bp.projectId) ?: continue
            if (projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return
        }
        error("No VIEW permission on any project in board ${board.id}")
    }
}

object WorkOpsBoardsMutation

@TypeController
class BoardMutationController(
    private val service: BoardService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
) : GraphQLController<WorkOpsBoardsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateBoardInput): Board {
        verifyManageOnScope(authentication, input.projectId, input.programId, input.portfolioId, input.projectIds)
        return service.create(input)
    }

    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateBoardInput): Board {
        val board = service.getById(id)
            ?: error("Board $id not found")
        verifyManageOnBoard(authentication, board)
        // Verify MANAGE on any newly-added projects.
        input.addProjectIds?.forEach { pid ->
            val project = projectService.getById(pid)
                ?: error("Project $pid not found")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        }
        return service.update(id, input)
    }

    @Field
    suspend fun addColumn(authentication: AuthenticationContext, input: CreateBoardColumnInput): BoardColumn {
        val board = service.getById(input.boardId)
            ?: error("Board ${input.boardId} not found")
        verifyManageOnBoard(authentication, board)
        return service.addColumn(input)
    }

    @Field
    suspend fun updateColumn(authentication: AuthenticationContext, input: UpdateBoardColumnInput): BoardColumn {
        val column = service.getColumnById(input.id)
            ?: error("Column ${input.id} not found")
        val board = service.getById(column.boardId)
            ?: error("Board ${column.boardId} not found")
        verifyManageOnBoard(authentication, board)
        return service.updateColumn(input)
    }

    @Field
    suspend fun deleteColumn(authentication: AuthenticationContext, columnId: UUID): Boolean {
        val column = service.getColumnById(columnId)
            ?: error("Column $columnId not found")
        val board = service.getById(column.boardId)
            ?: error("Board ${column.boardId} not found")
        verifyManageOnBoard(authentication, board)
        service.deleteColumn(columnId)
        return true
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, boardId: UUID): Boolean {
        val board = service.getById(boardId)
            ?: error("Board $boardId not found")
        verifyManageOnBoard(authentication, board)
        service.delete(boardId)
        return true
    }

    /**
     * Verifies MANAGE permission for creating a board. Checks the
     * parent entity or, for multi-project boards, all constituent projects.
     */
    private suspend fun verifyManageOnScope(
        authentication: AuthenticationContext,
        projectId: UUID?,
        programId: UUID?,
        portfolioId: UUID?,
        projectIds: List<UUID>?,
    ) {
        projectId?.let { pid ->
            val project = projectService.getById(pid)
                ?: error("Project $pid not found")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            return
        }
        programId?.let { pid ->
            val program = programService.getById(pid)
                ?: error("Program $pid not found")
            programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
            return
        }
        portfolioId?.let { pid ->
            val portfolio = portfolioService.getById(pid)
                ?: error("Portfolio $pid not found")
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
            return
        }
        if (!projectIds.isNullOrEmpty()) {
            for (pid in projectIds) {
                val project = projectService.getById(pid)
                    ?: error("Project $pid not found")
                projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            }
            return
        }
        error("Board requires a scope: projectId, programId, portfolioId, or projectIds")
    }

    /**
     * Verifies MANAGE permission on an existing board. For single-parent
     * boards, checks the parent. For multi-project boards, requires
     * MANAGE on ALL constituent projects.
     */
    private suspend fun verifyManageOnBoard(authentication: AuthenticationContext, board: Board) {
        board.projectId?.let { pid ->
            val project = projectService.getById(pid)
                ?: error("Project $pid not found")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            return
        }
        board.programId?.let { pid ->
            val program = programService.getById(pid)
                ?: error("Program $pid not found")
            programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
            return
        }
        board.portfolioId?.let { pid ->
            val portfolio = portfolioService.getById(pid)
                ?: error("Portfolio $pid not found")
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
            return
        }
        // Multi-project board: require MANAGE on all constituent projects.
        val boardProjects = service.listBoardProjects(board.id)
        if (boardProjects.isEmpty()) error("Board ${board.id} has no parent and no projects")
        for (bp in boardProjects) {
            val project = projectService.getById(bp.projectId)
                ?: error("Project ${bp.projectId} not found")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        }
    }
}
