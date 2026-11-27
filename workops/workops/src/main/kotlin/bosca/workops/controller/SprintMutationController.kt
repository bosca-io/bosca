package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.service.BoardService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.SprintService
import bosca.workops.service.StatusService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService

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
    private val taskService: TaskService,
    private val taskPermissions: TaskPermissionEvaluator,
    private val statusService: StatusService,
    private val profileService: ProfileService,
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
        val tasks = taskService.getByIds((sprint.committedTaskIds + input.committedTaskIds).distinct()).filter { it.deletedAt == null }
        for (task in tasks) taskPermissions.verifyAllowed(authentication, task, PermissionAction.EDIT)
        val (principalId, profileId) = actor(authentication)
        return service.start(input, principalId, profileId)
    }

    @Field
    suspend fun close(authentication: AuthenticationContext, input: CloseSprintInput): Sprint {
        val sprint = service.getById(input.sprintId)
            ?: error("Sprint ${input.sprintId} not found")
        verifyManageForBoard(authentication, sprint.boardId)
        val tasks = taskService.getByIds((sprint.committedTaskIds + sprint.addedDuringSprintTaskIds).distinct()).filter { it.deletedAt == null }
        val statuses = statusService.getByIds(tasks.map { it.statusId }.distinct()).associateBy { it.id }
        for (task in tasks) {
            if (requireNotNull(statuses[task.statusId]) { "Task status is unavailable." }.category !in setOf(StatusCategory.DONE, StatusCategory.CANCELLED)) {
                taskPermissions.verifyAllowed(authentication, task, PermissionAction.EDIT)
            }
        }
        val (principalId, profileId) = actor(authentication)
        return service.close(input, principalId, profileId)
    }

    private suspend fun actor(authentication: AuthenticationContext): Pair<UUID, UUID?> {
        val authenticated = authentication.principal() ?: error("Sprint lifecycle changes require an authenticated principal")
        val principal = authenticated.asPrincipal()
        return authenticated.id to (principal.primaryProfileId ?: profileService.getByPrincipal(principal.id).firstOrNull()?.id)
    }
}
