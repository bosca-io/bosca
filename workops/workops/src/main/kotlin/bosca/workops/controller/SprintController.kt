package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.board.Board
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.service.BoardService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.SprintService

@TypeController(type = "WorkOpsSprint")
class SprintTypeController(
    private val boardService: BoardService,
) : GraphQLController<Sprint> {

    @Field fun id(s: Sprint) = s.id
    @Field fun boardId(s: Sprint) = s.boardId
    @Field fun name(s: Sprint) = s.name
    @Field fun goal(s: Sprint) = s.goal
    @Field fun state(s: Sprint) = s.state
    @Field fun startDate(s: Sprint) = s.startDate
    @Field fun endDate(s: Sprint) = s.endDate
    @Field fun completeDate(s: Sprint) = s.completeDate
    @Field fun committedTaskIds(s: Sprint) = s.committedTaskIds
    @Field fun addedDuringSprintTaskIds(s: Sprint) = s.addedDuringSprintTaskIds
    @Field fun velocityPoints(s: Sprint) = s.velocityPoints
    @Field fun createdAt(s: Sprint) = s.createdAt
    @Field fun modifiedAt(s: Sprint) = s.modifiedAt
    @Field fun version(s: Sprint) = s.version

    @Field
    suspend fun board(sprint: Sprint): Board =
        boardService.getById(sprint.boardId)
            ?: error("Board ${sprint.boardId} missing for sprint ${sprint.id}")
}

object WorkOpsSprints

@TypeController
class SprintQueryController(
    private val service: SprintService,
    private val boardService: BoardService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
) : GraphQLController<WorkOpsSprints> {

    /**
     * Verifies VIEW on a board's parent. Single-parent boards delegate
     * to project/program/portfolio. Multi-project boards require VIEW
     * on at least one constituent project.
     */
    private suspend fun verifyViewOnBoard(authentication: AuthenticationContext, boardId: UUID) {
        val board = boardService.getById(boardId) ?: error("Board $boardId not found")
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
        val boardProjects = boardService.listBoardProjects(board.id)
        if (boardProjects.isEmpty()) error("Board ${board.id} has no parent and no projects")
        for (bp in boardProjects) {
            val project = projectService.getById(bp.projectId) ?: continue
            if (projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return
        }
        error("No VIEW permission on any project in board ${board.id}")
    }

    @Field
    suspend fun sprint(authentication: AuthenticationContext, id: UUID): Sprint? {
        val sprint = service.getById(id) ?: return null
        verifyViewOnBoard(authentication, sprint.boardId)
        return sprint
    }

    @Field
    suspend fun byBoard(
        authentication: AuthenticationContext,
        boardId: UUID,
        offset: Long,
        limit: Int,
    ): List<Sprint> {
        verifyViewOnBoard(authentication, boardId)
        return service.listByBoard(boardId, offset, limit)
    }
}

object WorkOpsSprintsMutation

@TypeController
class SprintMutationController(
    private val service: SprintService,
    private val boardService: BoardService,
    private val projectService: ProjectService,
    private val programService: ProgramService,
    private val portfolioService: PortfolioService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val portfolioPermissions: PortfolioPermissionEvaluator,
) : GraphQLController<WorkOpsSprintsMutation> {

    /**
     * Verifies MANAGE on a board's parent. Single-parent boards delegate
     * to project/program/portfolio. Multi-project boards require MANAGE
     * on all constituent projects.
     */
    private suspend fun verifyManageForBoard(authentication: AuthenticationContext, boardId: UUID) {
        val board = boardService.getById(boardId) ?: error("Board $boardId not found")
        board.projectId?.let { pid ->
            val project = projectService.getById(pid)
                ?: error("Project $pid not found for board ${board.id}")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
            return
        }
        board.programId?.let { pid ->
            val program = programService.getById(pid)
                ?: error("Program $pid not found for board ${board.id}")
            programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
            return
        }
        board.portfolioId?.let { pid ->
            val portfolio = portfolioService.getById(pid)
                ?: error("Portfolio $pid not found for board ${board.id}")
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
            return
        }
        val boardProjects = boardService.listBoardProjects(board.id)
        if (boardProjects.isEmpty()) error("Board ${board.id} has no parent and no projects")
        for (bp in boardProjects) {
            val project = projectService.getById(bp.projectId)
                ?: error("Project ${bp.projectId} not found")
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        }
    }

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateSprintInput): Sprint {
        verifyManageForBoard(authentication, input.boardId)
        return service.create(input)
    }

    @Field
    suspend fun start(authentication: AuthenticationContext, input: StartSprintInput): Sprint {
        val sprint = service.getById(input.sprintId)
            ?: error("Sprint ${input.sprintId} not found")
        verifyManageForBoard(authentication, sprint.boardId)
        return service.start(input)
    }

    @Field
    suspend fun close(authentication: AuthenticationContext, input: CloseSprintInput): Sprint {
        val sprint = service.getById(input.sprintId)
            ?: error("Sprint ${input.sprintId} not found")
        verifyManageForBoard(authentication, sprint.boardId)
        return service.close(input)
    }
}
