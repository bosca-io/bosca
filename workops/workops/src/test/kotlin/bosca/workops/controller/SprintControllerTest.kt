package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.BoardType
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.SprintState
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.service.BoardService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.SprintService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SprintControllerTest {

    private val service = mockk<SprintService>(relaxed = true)
    private val boardService = mockk<BoardService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val portfolioService = mockk<PortfolioService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val profileId = UUID.random()

    private fun queryController() = SprintQueryController(
        service,
        boardService,
        projectService,
        programService,
        portfolioService,
        projectPermissions,
        programPermissions,
        portfolioPermissions,
    )

    private fun mutationController() = SprintMutationController(
        service,
        boardService,
        projectService,
        programService,
        portfolioService,
        projectPermissions,
        programPermissions,
        portfolioPermissions,
    )

    @Test
    fun `sprint fields and board resolve`() = runTest {
        val board = sampleBoard()
        val sprint = sampleSprint(board.id)
        coEvery { boardService.getById(board.id) } returns board

        val controller = SprintTypeController(boardService)
        assertEquals(sprint.id, controller.id(sprint))
        assertEquals(sprint.boardId, controller.boardId(sprint))
        assertEquals(sprint.name, controller.name(sprint))
        assertEquals(sprint.goal, controller.goal(sprint))
        assertEquals(sprint.state, controller.state(sprint))
        assertEquals(sprint.startDate, controller.startDate(sprint))
        assertEquals(sprint.endDate, controller.endDate(sprint))
        assertEquals(sprint.completeDate, controller.completeDate(sprint))
        assertEquals(sprint.committedTaskIds, controller.committedTaskIds(sprint))
        assertEquals(sprint.addedDuringSprintTaskIds, controller.addedDuringSprintTaskIds(sprint))
        assertEquals(sprint.velocityPoints, controller.velocityPoints(sprint))
        assertEquals(sprint.createdAt, controller.createdAt(sprint))
        assertEquals(sprint.modifiedAt, controller.modifiedAt(sprint))
        assertEquals(sprint.version, controller.version(sprint))
        assertEquals(board, controller.board(sprint))

        val missingBoardSprint = sampleSprint(UUID.random())
        coEvery { boardService.getById(missingBoardSprint.boardId) } returns null
        assertFailsWith<IllegalStateException> { controller.board(missingBoardSprint) }
    }

    @Test
    fun `queries verify project program and portfolio parents`() = runTest {
        val project = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectBoard = sampleBoard(projectId = project.id)
        val programBoard = sampleBoard(programId = program.id)
        val portfolioBoard = sampleBoard(portfolioId = portfolio.id)
        val projectSprint = sampleSprint(projectBoard.id)
        val programSprint = sampleSprint(programBoard.id)
        val portfolioSprint = sampleSprint(portfolioBoard.id)
        listOf(projectBoard, programBoard, portfolioBoard).forEach {
            coEvery { boardService.getById(it.id) } returns it
        }
        coEvery { projectService.getById(project.id) } returns project
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.getById(projectSprint.id) } returns projectSprint
        coEvery { service.getById(programSprint.id) } returns programSprint
        coEvery { service.getById(portfolioSprint.id) } returns portfolioSprint
        coEvery { service.listByBoard(projectBoard.id, 2, 3) } returns listOf(projectSprint)

        val controller = queryController()
        assertEquals(projectSprint, controller.sprint(authentication, projectSprint.id))
        assertEquals(programSprint, controller.sprint(authentication, programSprint.id))
        assertEquals(portfolioSprint, controller.sprint(authentication, portfolioSprint.id))
        assertEquals(listOf(projectSprint), controller.byBoard(authentication, projectBoard.id, 2, 3))

        coVerify(exactly = 2) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        }
        coVerify(exactly = 1) {
            programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        }
        coVerify(exactly = 1) {
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.VIEW)
        }
    }

    @Test
    fun `multi project query skips missing projects and allows any visible project`() = runTest {
        val board = sampleBoard()
        val missingProjectId = UUID.random()
        val deniedProject = sampleProject()
        val visibleProject = sampleProject()
        val sprint = sampleSprint(board.id)
        coEvery { service.getById(sprint.id) } returns sprint
        coEvery { boardService.getById(board.id) } returns board
        coEvery { boardService.listBoardProjects(board.id) } returns listOf(
            BoardProject(board.id, missingProjectId),
            BoardProject(board.id, deniedProject.id),
            BoardProject(board.id, visibleProject.id),
        )
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { projectService.getById(deniedProject.id) } returns deniedProject
        coEvery { projectService.getById(visibleProject.id) } returns visibleProject
        coEvery {
            projectPermissions.isAllowed(authentication, deniedProject, PermissionAction.VIEW)
        } returns false
        coEvery {
            projectPermissions.isAllowed(authentication, visibleProject, PermissionAction.VIEW)
        } returns true

        assertEquals(sprint, queryController().sprint(authentication, sprint.id))
    }

    @Test
    fun `queries fail closed for missing resources and invisible multi project boards`() = runTest {
        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        assertNull(queryController().sprint(authentication, missingId))

        coEvery { boardService.getById(missingId) } returns null
        assertFailsWith<IllegalStateException> {
            queryController().byBoard(authentication, missingId, 0, 10)
        }

        val missingProjectBoard = sampleBoard(projectId = missingId)
        val missingProgramBoard = sampleBoard(programId = missingId)
        val missingPortfolioBoard = sampleBoard(portfolioId = missingId)
        val emptyBoard = sampleBoard()
        val invisibleBoard = sampleBoard()
        listOf(missingProjectBoard, missingProgramBoard, missingPortfolioBoard, emptyBoard, invisibleBoard).forEach {
            coEvery { boardService.getById(it.id) } returns it
        }
        coEvery { projectService.getById(missingId) } returns null
        coEvery { programService.getById(missingId) } returns null
        coEvery { portfolioService.getById(missingId) } returns null
        coEvery { boardService.listBoardProjects(emptyBoard.id) } returns emptyList()
        val deniedProject = sampleProject()
        coEvery { boardService.listBoardProjects(invisibleBoard.id) } returns listOf(
            BoardProject(invisibleBoard.id, deniedProject.id),
        )
        coEvery { projectService.getById(deniedProject.id) } returns deniedProject
        coEvery {
            projectPermissions.isAllowed(authentication, deniedProject, PermissionAction.VIEW)
        } returns false

        val controller = queryController()
        listOf(missingProjectBoard, missingProgramBoard, missingPortfolioBoard, emptyBoard, invisibleBoard).forEach {
            assertFailsWith<IllegalStateException> {
                controller.byBoard(authentication, it.id, 0, 10)
            }
        }
    }

    @Test
    fun `mutations verify each parent scope and delegate sprint lifecycle`() = runTest {
        val project = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectBoard = sampleBoard(projectId = project.id)
        val programBoard = sampleBoard(programId = program.id)
        val portfolioBoard = sampleBoard(portfolioId = portfolio.id)
        val created = sampleSprint(projectBoard.id)
        val started = sampleSprint(programBoard.id).copy(state = SprintState.ACTIVE)
        val closed = sampleSprint(portfolioBoard.id).copy(state = SprintState.CLOSED)
        val createInput = CreateSprintInput(projectBoard.id, "Sprint 1", "Ship it")
        val startInput = StartSprintInput(started.id, expectedVersion = 3)
        val closeInput = CloseSprintInput(closed.id, expectedVersion = 4, velocityPoints = 21.0)
        listOf(projectBoard, programBoard, portfolioBoard).forEach {
            coEvery { boardService.getById(it.id) } returns it
        }
        coEvery { projectService.getById(project.id) } returns project
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.getById(started.id) } returns started
        coEvery { service.getById(closed.id) } returns closed
        coEvery { service.create(createInput) } returns created
        coEvery { service.start(startInput) } returns started
        coEvery { service.close(closeInput) } returns closed

        val controller = mutationController()
        assertEquals(created, controller.create(authentication, createInput))
        assertEquals(started, controller.start(authentication, startInput))
        assertEquals(closed, controller.close(authentication, closeInput))

        coVerify(exactly = 1) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) {
            programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) {
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) { service.create(createInput) }
        coVerify(exactly = 1) { service.start(startInput) }
        coVerify(exactly = 1) { service.close(closeInput) }
    }

    @Test
    fun `multi project mutation requires manage on every project`() = runTest {
        val board = sampleBoard()
        val firstProject = sampleProject()
        val secondProject = sampleProject()
        val input = CreateSprintInput(board.id, "Shared sprint")
        val sprint = sampleSprint(board.id)
        coEvery { boardService.getById(board.id) } returns board
        coEvery { boardService.listBoardProjects(board.id) } returns listOf(
            BoardProject(board.id, firstProject.id),
            BoardProject(board.id, secondProject.id),
        )
        coEvery { projectService.getById(firstProject.id) } returns firstProject
        coEvery { projectService.getById(secondProject.id) } returns secondProject
        coEvery { service.create(input) } returns sprint

        assertEquals(sprint, mutationController().create(authentication, input))
        coVerify(exactly = 1) {
            projectPermissions.verifyAllowed(authentication, firstProject, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) {
            projectPermissions.verifyAllowed(authentication, secondProject, PermissionAction.MANAGE)
        }
    }

    @Test
    fun `mutations fail closed for missing sprint board parents and multi project members`() = runTest {
        val missingId = UUID.random()
        val createForMissingBoard = CreateSprintInput(missingId, "Missing")
        coEvery { boardService.getById(missingId) } returns null
        coEvery { service.getById(missingId) } returns null
        val controller = mutationController()
        assertFailsWith<IllegalStateException> { controller.create(authentication, createForMissingBoard) }
        assertFailsWith<IllegalStateException> {
            controller.start(authentication, StartSprintInput(missingId, expectedVersion = 0))
        }
        assertFailsWith<IllegalStateException> {
            controller.close(authentication, CloseSprintInput(missingId, expectedVersion = 0))
        }

        val missingProjectBoard = sampleBoard(projectId = missingId)
        val missingProgramBoard = sampleBoard(programId = missingId)
        val missingPortfolioBoard = sampleBoard(portfolioId = missingId)
        val emptyBoard = sampleBoard()
        val missingMemberBoard = sampleBoard()
        listOf(missingProjectBoard, missingProgramBoard, missingPortfolioBoard, emptyBoard, missingMemberBoard).forEach {
            coEvery { boardService.getById(it.id) } returns it
        }
        coEvery { projectService.getById(missingId) } returns null
        coEvery { programService.getById(missingId) } returns null
        coEvery { portfolioService.getById(missingId) } returns null
        coEvery { boardService.listBoardProjects(emptyBoard.id) } returns emptyList()
        coEvery { boardService.listBoardProjects(missingMemberBoard.id) } returns listOf(
            BoardProject(missingMemberBoard.id, missingId),
        )

        listOf(missingProjectBoard, missingProgramBoard, missingPortfolioBoard, emptyBoard, missingMemberBoard).forEach {
            assertFailsWith<IllegalStateException> {
                controller.create(authentication, CreateSprintInput(it.id, "Invalid"))
            }
        }
    }

    private fun sampleBoard(
        projectId: UUID? = null,
        programId: UUID? = null,
        portfolioId: UUID? = null,
    ) = Board(
        id = UUID.random(),
        projectId = projectId,
        programId = programId,
        portfolioId = portfolioId,
        name = "Board",
        type = BoardType.SCRUM,
    )

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJECT",
        name = "Project",
        ownerProfileId = profileId,
    )

    private fun sampleProgram() = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = profileId,
    )

    private fun samplePortfolio() = Portfolio(
        id = UUID.random(),
        key = "PORTFOLIO",
        name = "Portfolio",
        ownerProfileId = profileId,
    )

    private fun sampleSprint(boardId: UUID): Sprint {
        val createdAt = OffsetDateTime.now()
        return Sprint(
            id = UUID.random(),
            boardId = boardId,
            name = "Sprint 1",
            goal = "Ship it",
            state = SprintState.FUTURE,
            startDate = createdAt,
            endDate = createdAt,
            completeDate = createdAt,
            committedTaskIds = listOf(UUID.random()),
            addedDuringSprintTaskIds = listOf(UUID.random()),
            velocityPoints = 13.0,
            createdAt = createdAt,
            modifiedAt = createdAt,
            version = 2,
        )
    }
}
