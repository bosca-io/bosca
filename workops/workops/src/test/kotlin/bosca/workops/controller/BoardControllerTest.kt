package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardColumn
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.BoardType
import bosca.workops.model.board.CreateBoardColumnInput
import bosca.workops.model.board.CreateBoardInput
import bosca.workops.model.board.SwimlaneStrategy
import bosca.workops.model.board.UpdateBoardColumnInput
import bosca.workops.model.board.UpdateBoardInput
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.workflow.Status
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardControllerTest {

    private val service = mockk<BoardService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val portfolioService = mockk<PortfolioService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val profileId = UUID.random()

    private fun mutationController() = BoardMutationController(
        service,
        projectService,
        programService,
        portfolioService,
        projectPermissions,
        programPermissions,
        portfolioPermissions,
    )

    private fun queryController() = BoardQueryController(
        service,
        projectService,
        programService,
        portfolioService,
        projectPermissions,
        programPermissions,
        portfolioPermissions,
    )

    @Test
    fun `board and column related fields resolve nullable parents and collections`() = runTest {
        val sprintService = mockk<SprintService>()
        val statusService = mockk<StatusService>()
        val project = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val board = sampleBoard(project.id, program.id, portfolio.id)
        val sharedBoard = sampleBoard()
        val column = BoardColumn(
            id = UUID.random(),
            boardId = board.id,
            name = "In progress",
            displayOrder = 1,
            statusIds = listOf(UUID.random()),
        )
        val sprint = Sprint(boardId = board.id, name = "Sprint 1")
        val status = Status(
            id = column.statusIds.single(),
            name = "In progress",
            category = StatusCategory.IN_PROGRESS,
            colorHex = "#123456",
        )
        coEvery { projectService.getById(project.id) } returns project
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.listBoardProjects(board.id) } returns listOf(BoardProject(board.id, project.id))
        coEvery { service.listBoardProjects(sharedBoard.id) } returns emptyList()
        coEvery { projectService.getByIds(listOf(project.id)) } returns listOf(project)
        coEvery { service.listColumns(board.id) } returns listOf(column)
        coEvery { sprintService.listByBoard(board.id, 2, 3) } returns listOf(sprint)
        coEvery { statusService.getByIds(column.statusIds) } returns listOf(status)

        val boardController = BoardTypeController(
            service,
            sprintService,
            projectService,
            programService,
            portfolioService,
        )
        assertEquals(project, boardController.project(board))
        assertEquals(program, boardController.program(board))
        assertEquals(portfolio, boardController.portfolio(board))
        assertNull(boardController.project(sharedBoard))
        assertNull(boardController.program(sharedBoard))
        assertNull(boardController.portfolio(sharedBoard))
        assertEquals(listOf(project), boardController.projects(board))
        assertTrue(boardController.projects(sharedBoard).isEmpty())
        assertEquals(listOf(column), boardController.columns(board))
        assertEquals(listOf(sprint), boardController.sprints(board, 2, 3))
        assertEquals(listOf(status), BoardColumnTypeController(statusService).statuses(column))
    }

    @Test
    fun `create verifies project program portfolio and multi project scopes`() = runTest {
        val project = sampleProject()
        val secondProject = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectInput = CreateBoardInput(projectId = project.id, name = "Project", type = BoardType.KANBAN)
        val programInput = CreateBoardInput(programId = program.id, name = "Program", type = BoardType.SCRUM)
        val portfolioInput = CreateBoardInput(portfolioId = portfolio.id, name = "Portfolio", type = BoardType.KANBAN)
        val multiInput = CreateBoardInput(
            projectIds = listOf(project.id, secondProject.id),
            name = "Shared",
            type = BoardType.SCRUM,
        )
        val projectBoard = sampleBoard(projectId = project.id)
        val programBoard = sampleBoard(programId = program.id)
        val portfolioBoard = sampleBoard(portfolioId = portfolio.id)
        val multiBoard = sampleBoard()
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectService.getById(secondProject.id) } returns secondProject
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.create(projectInput) } returns projectBoard
        coEvery { service.create(programInput) } returns programBoard
        coEvery { service.create(portfolioInput) } returns portfolioBoard
        coEvery { service.create(multiInput) } returns multiBoard

        assertEquals(projectBoard, mutationController().create(authentication, projectInput))
        assertEquals(programBoard, mutationController().create(authentication, programInput))
        assertEquals(portfolioBoard, mutationController().create(authentication, portfolioInput))
        assertEquals(multiBoard, mutationController().create(authentication, multiInput))

        coVerify(exactly = 3) { projectPermissions.verifyAllowed(authentication, any(), PermissionAction.MANAGE) }
        coVerify(exactly = 1) { programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE) }
    }

    @Test
    fun `board and column lifecycle verifies every existing board scope`() = runTest {
        val project = sampleProject()
        val addedProject = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectBoard = sampleBoard(projectId = project.id)
        val programBoard = sampleBoard(programId = program.id)
        val portfolioBoard = sampleBoard(portfolioId = portfolio.id)
        val multiBoard = sampleBoard()
        val multiProjects = listOf(sampleProject(), sampleProject())
        val updateInput = UpdateBoardInput(
            name = "Updated",
            type = BoardType.SCRUM,
            swimlaneStrategy = SwimlaneStrategy.ASSIGNEE,
            expectedVersion = 1,
            addProjectIds = listOf(addedProject.id),
        )
        val createColumnInput = CreateBoardColumnInput(
            boardId = programBoard.id,
            name = "In progress",
            displayOrder = 2,
            statusIds = listOf(UUID.random()),
        )
        val column = BoardColumn(
            id = UUID.random(),
            boardId = portfolioBoard.id,
            name = "Done",
            displayOrder = 3,
            statusIds = listOf(UUID.random()),
        )
        val updateColumnInput = UpdateBoardColumnInput(
            id = column.id,
            name = "Complete",
            displayOrder = 4,
            statusIds = column.statusIds,
        )
        val multiColumn = column.copy(id = UUID.random(), boardId = multiBoard.id)
        coEvery { service.getById(projectBoard.id) } returns projectBoard
        coEvery { service.getById(programBoard.id) } returns programBoard
        coEvery { service.getById(portfolioBoard.id) } returns portfolioBoard
        coEvery { service.getById(multiBoard.id) } returns multiBoard
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectService.getById(addedProject.id) } returns addedProject
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        multiProjects.forEach { coEvery { projectService.getById(it.id) } returns it }
        coEvery { service.listBoardProjects(multiBoard.id) } returns
            multiProjects.map { BoardProject(multiBoard.id, it.id) }
        coEvery { service.update(projectBoard.id, updateInput) } returns projectBoard.copy(name = updateInput.name)
        coEvery { service.addColumn(createColumnInput) } returns column.copy(boardId = programBoard.id)
        coEvery { service.getColumnById(column.id) } returns column
        coEvery { service.updateColumn(updateColumnInput) } returns column.copy(name = updateColumnInput.name)
        coEvery { service.getColumnById(multiColumn.id) } returns multiColumn

        assertEquals(updateInput.name, mutationController().update(authentication, projectBoard.id, updateInput).name)
        assertEquals(programBoard.id, mutationController().addColumn(authentication, createColumnInput).boardId)
        assertEquals(updateColumnInput.name, mutationController().updateColumn(authentication, updateColumnInput).name)
        assertTrue(mutationController().deleteColumn(authentication, multiColumn.id))
        assertTrue(mutationController().delete(authentication, projectBoard.id))

        coVerify(exactly = 1) { projectPermissions.verifyAllowed(authentication, addedProject, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { service.deleteColumn(multiColumn.id) }
        coVerify(exactly = 1) { service.delete(projectBoard.id) }
        multiProjects.forEach {
            coVerify(exactly = 1) { projectPermissions.verifyAllowed(authentication, it, PermissionAction.MANAGE) }
        }
    }

    @Test
    fun `board queries resolve each scope and require one visible project for shared boards`() = runTest {
        val project = sampleProject()
        val secondProject = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectBoard = sampleBoard(projectId = project.id)
        val programBoard = sampleBoard(programId = program.id)
        val portfolioBoard = sampleBoard(portfolioId = portfolio.id)
        val multiBoard = sampleBoard()
        val missingProjectId = UUID.random()
        coEvery { service.getById(projectBoard.id) } returns projectBoard
        coEvery { service.getById(programBoard.id) } returns programBoard
        coEvery { service.getById(portfolioBoard.id) } returns portfolioBoard
        coEvery { service.getById(multiBoard.id) } returns multiBoard
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectService.getById(secondProject.id) } returns secondProject
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.listByProject(project.id) } returns listOf(projectBoard)
        coEvery { service.listByProgram(program.id) } returns listOf(programBoard)
        coEvery { service.listByPortfolio(portfolio.id) } returns listOf(portfolioBoard)
        coEvery { service.listMultiProjectByProjectIds(listOf(project.id, secondProject.id)) } returns listOf(multiBoard)
        coEvery { service.listBoardProjects(multiBoard.id) } returns listOf(
            BoardProject(multiBoard.id, missingProjectId),
            BoardProject(multiBoard.id, project.id),
            BoardProject(multiBoard.id, secondProject.id),
        )
        coEvery { projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW) } returns false
        coEvery { projectPermissions.isAllowed(authentication, secondProject, PermissionAction.VIEW) } returns true

        assertEquals(projectBoard, queryController().board(authentication, projectBoard.id))
        assertEquals(programBoard, queryController().board(authentication, programBoard.id))
        assertEquals(portfolioBoard, queryController().board(authentication, portfolioBoard.id))
        assertEquals(multiBoard, queryController().board(authentication, multiBoard.id))
        assertEquals(listOf(projectBoard), queryController().byProject(authentication, project.id))
        assertEquals(listOf(programBoard), queryController().byProgram(authentication, program.id))
        assertEquals(listOf(portfolioBoard), queryController().byPortfolio(authentication, portfolio.id))
        assertEquals(
            listOf(multiBoard),
            queryController().byProjects(authentication, listOf(project.id, secondProject.id)),
        )
    }

    @Test
    fun `board mutations and queries fail closed for missing or unscoped records`() = runTest {
        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        coEvery { service.getColumnById(missingId) } returns null
        coEvery { projectService.getById(missingId) } returns null
        coEvery { programService.getById(missingId) } returns null
        coEvery { portfolioService.getById(missingId) } returns null
        assertNull(queryController().board(authentication, missingId))
        assertFailsWith<IllegalStateException> { queryController().byProject(authentication, missingId) }
        assertFailsWith<IllegalStateException> { queryController().byProgram(authentication, missingId) }
        assertFailsWith<IllegalStateException> { queryController().byPortfolio(authentication, missingId) }
        assertFailsWith<IllegalStateException> { queryController().byProjects(authentication, listOf(missingId)) }
        assertFailsWith<IllegalStateException> {
            mutationController().create(
                authentication,
                CreateBoardInput(name = "Unscoped", type = BoardType.KANBAN),
            )
        }
        assertFailsWith<IllegalStateException> {
            mutationController().create(
                authentication,
                CreateBoardInput(projectId = missingId, name = "Missing", type = BoardType.KANBAN),
            )
        }
        assertFailsWith<IllegalStateException> {
            mutationController().create(
                authentication,
                CreateBoardInput(programId = missingId, name = "Missing", type = BoardType.KANBAN),
            )
        }
        assertFailsWith<IllegalStateException> {
            mutationController().create(
                authentication,
                CreateBoardInput(portfolioId = missingId, name = "Missing", type = BoardType.KANBAN),
            )
        }
        assertFailsWith<IllegalStateException> { mutationController().update(authentication, missingId, sampleUpdate()) }
        assertFailsWith<IllegalStateException> {
            mutationController().addColumn(
                authentication,
                CreateBoardColumnInput(missingId, "Missing", 0, emptyList()),
            )
        }
        assertFailsWith<IllegalStateException> { mutationController().updateColumn(authentication, sampleColumnUpdate(missingId)) }
        assertFailsWith<IllegalStateException> { mutationController().deleteColumn(authentication, missingId) }
        assertFailsWith<IllegalStateException> { mutationController().delete(authentication, missingId) }

        val emptySharedBoard = sampleBoard()
        coEvery { service.getById(emptySharedBoard.id) } returns emptySharedBoard
        coEvery { service.listBoardProjects(emptySharedBoard.id) } returns emptyList()
        assertFailsWith<IllegalStateException> { queryController().board(authentication, emptySharedBoard.id) }
        assertFailsWith<IllegalStateException> { mutationController().delete(authentication, emptySharedBoard.id) }
    }

    @Test
    fun `board queries fail closed for missing parents and invisible shared boards`() = runTest {
        val missingProjectId = UUID.random()
        val missingProgramId = UUID.random()
        val missingPortfolioId = UUID.random()
        val deniedProject = sampleProject()
        val projectBoard = sampleBoard(projectId = missingProjectId)
        val programBoard = sampleBoard(programId = missingProgramId)
        val portfolioBoard = sampleBoard(portfolioId = missingPortfolioId)
        val sharedBoard = sampleBoard()
        listOf(projectBoard, programBoard, portfolioBoard, sharedBoard).forEach {
            coEvery { service.getById(it.id) } returns it
        }
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { programService.getById(missingProgramId) } returns null
        coEvery { portfolioService.getById(missingPortfolioId) } returns null
        coEvery { service.listBoardProjects(sharedBoard.id) } returns
            listOf(BoardProject(sharedBoard.id, deniedProject.id))
        coEvery { projectService.getById(deniedProject.id) } returns deniedProject
        coEvery {
            projectPermissions.isAllowed(authentication, deniedProject, PermissionAction.VIEW)
        } returns false

        val controller = queryController()
        assertFailsWith<IllegalStateException> { controller.board(authentication, projectBoard.id) }
        assertFailsWith<IllegalStateException> { controller.board(authentication, programBoard.id) }
        assertFailsWith<IllegalStateException> { controller.board(authentication, portfolioBoard.id) }
        assertFailsWith<IllegalStateException> { controller.board(authentication, sharedBoard.id) }
    }

    @Test
    fun `board mutations fail closed when nested resources disappear`() = runTest {
        val missingProjectId = UUID.random()
        val missingProgramId = UUID.random()
        val missingPortfolioId = UUID.random()
        val missingBoardId = UUID.random()
        val managedProject = sampleProject()
        val managedBoard = sampleBoard(projectId = managedProject.id)
        val projectBoard = sampleBoard(projectId = missingProjectId)
        val programBoard = sampleBoard(programId = missingProgramId)
        val portfolioBoard = sampleBoard(portfolioId = missingPortfolioId)
        val sharedBoard = sampleBoard()
        val updateColumn = sampleColumnUpdate(UUID.random())
        val deleteColumn = BoardColumn(
            id = UUID.random(),
            boardId = missingBoardId,
            name = "Missing board",
            displayOrder = 0,
            statusIds = emptyList(),
        )
        val updateColumnRecord = deleteColumn.copy(id = updateColumn.id)
        listOf(managedBoard, projectBoard, programBoard, portfolioBoard, sharedBoard).forEach {
            coEvery { service.getById(it.id) } returns it
        }
        coEvery { service.getById(missingBoardId) } returns null
        coEvery { service.getColumnById(updateColumn.id) } returns updateColumnRecord
        coEvery { service.getColumnById(deleteColumn.id) } returns deleteColumn
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { projectService.getById(managedProject.id) } returns managedProject
        coEvery { programService.getById(missingProgramId) } returns null
        coEvery { portfolioService.getById(missingPortfolioId) } returns null
        coEvery { service.listBoardProjects(sharedBoard.id) } returns
            listOf(BoardProject(sharedBoard.id, missingProjectId))
        val updateWithoutProjects = sampleUpdate()
        coEvery { service.update(managedBoard.id, updateWithoutProjects) } returns managedBoard

        val controller = mutationController()
        assertEquals(managedBoard, controller.update(authentication, managedBoard.id, updateWithoutProjects))
        assertFailsWith<IllegalStateException> {
            controller.update(
                authentication,
                managedBoard.id,
                sampleUpdate().copy(addProjectIds = listOf(missingProjectId)),
            )
        }
        assertFailsWith<IllegalStateException> { controller.updateColumn(authentication, updateColumn) }
        assertFailsWith<IllegalStateException> { controller.deleteColumn(authentication, deleteColumn.id) }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, projectBoard.id) }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, programBoard.id) }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, portfolioBoard.id) }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, sharedBoard.id) }
        assertFailsWith<IllegalStateException> {
            controller.create(
                authentication,
                CreateBoardInput(
                    projectIds = listOf(missingProjectId),
                    name = "Missing project",
                    type = BoardType.KANBAN,
                ),
            )
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
        type = BoardType.KANBAN,
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

    private fun sampleUpdate() = UpdateBoardInput(
        name = "Updated",
        type = BoardType.KANBAN,
        swimlaneStrategy = SwimlaneStrategy.NONE,
        expectedVersion = 0,
    )

    private fun sampleColumnUpdate(id: UUID) = UpdateBoardColumnInput(
        id = id,
        name = "Updated",
        displayOrder = 0,
        statusIds = emptyList(),
    )
}
