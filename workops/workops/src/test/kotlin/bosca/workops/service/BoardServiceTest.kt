package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardColumn
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.BoardType
import bosca.workops.model.board.CreateBoardColumnInput
import bosca.workops.model.board.CreateBoardInput
import bosca.workops.model.board.SwimlaneStrategy
import bosca.workops.model.board.UpdateBoardColumnInput
import bosca.workops.model.board.UpdateBoardInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.BoardRepository
import bosca.workops.repository.StatusRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `lookups and direct mutations delegate while empty batches avoid the repository`() = runTest {
        val repository = mockk<BoardRepository>()
        val statusRepository = mockk<StatusRepository>()
        val board = sampleBoard()
        val projectId = board.projectId ?: error("missing project id")
        val programId = UUID.random()
        val portfolioId = UUID.random()
        val boardProject = BoardProject(board.id, projectId)
        val column = sampleColumn(board.id)
        coEvery { repository.getById(board.id) } returns board
        coEvery { repository.getByIds(listOf(board.id)) } returns listOf(board)
        coEvery { repository.listByProject(projectId) } returns listOf(board)
        coEvery { repository.listByProgram(programId) } returns listOf(board)
        coEvery { repository.listByPortfolio(portfolioId) } returns listOf(board)
        coEvery { repository.listMultiProjectByProjectIds(listOf(projectId)) } returns listOf(board)
        coEvery { repository.listBoardProjects(board.id) } returns listOf(boardProject)
        coEvery { repository.addBoardProject(board.id, projectId) } returns boardProject
        coEvery { repository.removeBoardProject(board.id, projectId) } just Runs
        coEvery { repository.getColumnById(column.id) } returns column
        coEvery { repository.listColumns(board.id) } returns listOf(column)
        coEvery { repository.deleteColumn(column.id) } just Runs
        coEvery { repository.deleteById(board.id) } just Runs
        val service = BoardServiceImpl(repository, statusRepository)

        assertEquals(board, service.getById(board.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(board), service.getByIds(listOf(board.id)))
        assertEquals(listOf(board), service.listByProject(projectId))
        assertEquals(listOf(board), service.listByProgram(programId))
        assertEquals(listOf(board), service.listByPortfolio(portfolioId))
        assertTrue(service.listMultiProjectByProjectIds(emptyList()).isEmpty())
        assertEquals(listOf(board), service.listMultiProjectByProjectIds(listOf(projectId)))
        assertEquals(listOf(boardProject), service.listBoardProjects(board.id))
        assertEquals(boardProject, service.addBoardProject(board.id, projectId))
        service.removeBoardProject(board.id, projectId)
        assertEquals(column, service.getColumnById(column.id))
        assertEquals(listOf(column), service.listColumns(board.id))
        service.deleteColumn(column.id)
        service.delete(board.id)

        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
        coVerify(exactly = 0) { repository.listMultiProjectByProjectIds(emptyList()) }
    }

    @Test
    fun `create rejects blank names and invalid scopes before adding a board`() = runTest {
        val repository = mockk<BoardRepository>()
        val statusRepository = mockk<StatusRepository>()
        val projectId = UUID.random()
        val service = BoardServiceImpl(repository, statusRepository)

        val blankName = assertFailsWith<WorkOpsValidationException> {
            service.create(CreateBoardInput(projectId = projectId, name = "   ", type = BoardType.KANBAN))
        }
        val multipleParents = assertFailsWith<WorkOpsValidationException> {
            service.create(
                CreateBoardInput(
                    projectId = projectId,
                    programId = UUID.random(),
                    name = "Delivery",
                    type = BoardType.KANBAN,
                ),
            )
        }
        val missingScope = assertFailsWith<WorkOpsValidationException> {
            service.create(CreateBoardInput(name = "Delivery", type = BoardType.KANBAN))
        }
        val conflictingProjectScopes = assertFailsWith<WorkOpsValidationException> {
            service.create(
                CreateBoardInput(
                    projectId = projectId,
                    projectIds = listOf(projectId),
                    name = "Delivery",
                    type = BoardType.KANBAN,
                ),
            )
        }

        assertEquals("name", blankName.field)
        assertEquals("scope", multipleParents.field)
        assertEquals("scope", missingScope.field)
        assertEquals("scope", conflictingProjectScopes.field)
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `create links single and distinct multi project scopes`() = runTest {
        val repository = mockk<BoardRepository>()
        val statusRepository = mockk<StatusRepository>()
        val firstProjectId = UUID.random()
        val secondProjectId = UUID.random()
        val singleBoard = sampleBoard(projectId = firstProjectId)
        val multiBoard = sampleBoard(projectId = null)
        coEvery {
            repository.add(firstProjectId, null, null, "Single", BoardType.KANBAN, SwimlaneStrategy.NONE)
        } returns singleBoard
        coEvery { repository.addBoardProject(singleBoard.id, firstProjectId) } returns
                BoardProject(singleBoard.id, firstProjectId)
        coEvery {
            repository.add(null, null, null, "Multi", BoardType.SCRUM, SwimlaneStrategy.PROJECT)
        } returns multiBoard
        coEvery { repository.addBoardProject(multiBoard.id, firstProjectId) } returns
                BoardProject(multiBoard.id, firstProjectId)
        coEvery { repository.addBoardProject(multiBoard.id, secondProjectId) } returns
                BoardProject(multiBoard.id, secondProjectId)
        val service = BoardServiceImpl(repository, statusRepository)

        assertEquals(
            singleBoard,
            service.create(CreateBoardInput(projectId = firstProjectId, name = "Single", type = BoardType.KANBAN)),
        )
        assertEquals(
            multiBoard,
            service.create(
                CreateBoardInput(
                    projectIds = listOf(firstProjectId, firstProjectId, secondProjectId),
                    name = "Multi",
                    type = BoardType.SCRUM,
                    swimlaneStrategy = SwimlaneStrategy.PROJECT,
                ),
            ),
        )

        coVerify(exactly = 1) { repository.addBoardProject(multiBoard.id, firstProjectId) }
        coVerify(exactly = 1) { repository.addBoardProject(multiBoard.id, secondProjectId) }
    }

    @Test
    fun `update validates names manages distinct project links and reports stale boards`() = runTest {
        val repository = mockk<BoardRepository>()
        val statusRepository = mockk<StatusRepository>()
        val board = sampleBoard()
        val addedProjectId = UUID.random()
        val removedProjectId = UUID.random()
        val validInput = UpdateBoardInput(
            name = "Updated",
            type = BoardType.SCRUM,
            swimlaneStrategy = SwimlaneStrategy.ASSIGNEE,
            expectedVersion = 4,
            addProjectIds = listOf(addedProjectId, addedProjectId),
            removeProjectIds = listOf(removedProjectId, removedProjectId),
        )
        coEvery { repository.addBoardProject(board.id, addedProjectId) } coAnswers {
            yield()
            BoardProject(board.id, addedProjectId)
        }
        coEvery { repository.removeBoardProject(board.id, removedProjectId) } coAnswers {
            yield()
        }
        coEvery {
            repository.update(board.id, "Updated", BoardType.SCRUM, SwimlaneStrategy.ASSIGNEE, 4)
        } returns board.copy(
            name = "Updated",
            type = BoardType.SCRUM,
            swimlaneStrategy = SwimlaneStrategy.ASSIGNEE,
            version = 5,
        )
        coEvery {
            repository.update(board.id, "Stale", BoardType.KANBAN, SwimlaneStrategy.NONE, 2)
        } returns null
        coEvery {
            repository.update(board.id, "No Links", BoardType.KANBAN, SwimlaneStrategy.NONE, 5)
        } returns board.copy(name = "No Links", version = 6)
        val service = BoardServiceImpl(repository, statusRepository)

        val blankName = assertFailsWith<WorkOpsValidationException> {
            service.update(
                board.id,
                UpdateBoardInput(" ", BoardType.KANBAN, SwimlaneStrategy.NONE, expectedVersion = 1),
            )
        }
        val updated = service.update(board.id, validInput)
        val updatedWithoutLinks = service.update(
            board.id,
            UpdateBoardInput(
                "No Links",
                BoardType.KANBAN,
                SwimlaneStrategy.NONE,
                expectedVersion = 5,
                addProjectIds = emptyList(),
                removeProjectIds = emptyList(),
            ),
        )
        val stale = assertFailsWith<OptimisticLockFailedException> {
            service.update(
                board.id,
                UpdateBoardInput("Stale", BoardType.KANBAN, SwimlaneStrategy.NONE, expectedVersion = 2),
            )
        }

        assertEquals("name", blankName.field)
        assertEquals("Updated", updated.name)
        assertEquals("No Links", updatedWithoutLinks.name)
        assertEquals("Board", stale.type)
        assertEquals(board.id, stale.id)
        coVerify(exactly = 1) { repository.addBoardProject(board.id, addedProjectId) }
        coVerify(exactly = 1) { repository.removeBoardProject(board.id, removedProjectId) }
    }

    @Test
    fun `add column validates its name statuses and preserves the requested values`() = runTest {
        val repository = mockk<BoardRepository>()
        val statusRepository = mockk<StatusRepository>()
        val boardId = UUID.random()
        val status = sampleStatus()
        val unknownStatusId = UUID.random()
        coEvery { statusRepository.getByIds(listOf(unknownStatusId)) } returns emptyList()
        coEvery { statusRepository.getByIds(listOf(status.id, status.id)) } returns listOf(status)
        coEvery { repository.addColumn(any()) } coAnswers { firstArg<BoardColumn>().copy(id = UUID.random()) }
        val service = BoardServiceImpl(repository, statusRepository)

        val blankName = assertFailsWith<WorkOpsValidationException> {
            service.addColumn(CreateBoardColumnInput(boardId, " ", 0, listOf(status.id)))
        }
        val emptyStatuses = assertFailsWith<WorkOpsValidationException> {
            service.addColumn(CreateBoardColumnInput(boardId, "Ready", 0, emptyList()))
        }
        val unknownStatus = assertFailsWith<WorkOpsValidationException> {
            service.addColumn(CreateBoardColumnInput(boardId, "Ready", 0, listOf(unknownStatusId)))
        }
        val added = service.addColumn(
            CreateBoardColumnInput(boardId, "Ready", 8, listOf(status.id, status.id), wipLimit = 3),
        )

        assertEquals("name", blankName.field)
        assertEquals("statusIds", emptyStatuses.field)
        assertEquals("statusIds", unknownStatus.field)
        assertEquals(boardId, added.boardId)
        assertEquals("Ready", added.name)
        assertEquals(8, added.displayOrder)
        assertEquals(listOf(status.id, status.id), added.statusIds)
        assertEquals(3, added.wipLimit)
        coVerify(exactly = 0) { statusRepository.getByIds(emptyList()) }
    }

    @Test
    fun `update column validates statuses and reports missing columns`() = runTest {
        val repository = mockk<BoardRepository>()
        val statusRepository = mockk<StatusRepository>()
        val columnId = UUID.random()
        val status = sampleStatus()
        val unknownStatusId = UUID.random()
        coEvery { statusRepository.getByIds(listOf(unknownStatusId)) } returns emptyList()
        coEvery { statusRepository.getByIds(listOf(status.id)) } returns listOf(status)
        coEvery { repository.updateColumn(match { it.name == "Missing" }) } returns null
        coEvery { repository.updateColumn(match { it.name == "Complete" }) } coAnswers { firstArg() }
        val service = BoardServiceImpl(repository, statusRepository)

        val blankName = assertFailsWith<WorkOpsValidationException> {
            service.updateColumn(UpdateBoardColumnInput(columnId, " ", 0, listOf(status.id)))
        }
        val emptyStatuses = assertFailsWith<WorkOpsValidationException> {
            service.updateColumn(UpdateBoardColumnInput(columnId, "Ready", 0, emptyList()))
        }
        val unknownStatus = assertFailsWith<WorkOpsValidationException> {
            service.updateColumn(UpdateBoardColumnInput(columnId, "Ready", 0, listOf(unknownStatusId)))
        }
        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.updateColumn(UpdateBoardColumnInput(columnId, "Missing", 1, listOf(status.id)))
        }
        val updated = service.updateColumn(
            UpdateBoardColumnInput(columnId, "Complete", 2, listOf(status.id), wipLimit = 5),
        )

        assertEquals("name", blankName.field)
        assertEquals("statusIds", emptyStatuses.field)
        assertEquals("statusIds", unknownStatus.field)
        assertEquals("BoardColumn", missing.type)
        assertEquals(columnId.toString(), missing.handle)
        assertEquals(UUID.NIL, updated.boardId)
        assertEquals("Complete", updated.name)
        assertEquals(5, updated.wipLimit)
        coVerify(exactly = 0) { statusRepository.getByIds(emptyList()) }
    }

    private fun sampleBoard(projectId: UUID? = UUID.random()) = Board(
        id = UUID.random(),
        projectId = projectId,
        name = "Delivery",
        type = BoardType.KANBAN,
    )

    private fun sampleColumn(boardId: UUID) = BoardColumn(
        id = UUID.random(),
        boardId = boardId,
        name = "Ready",
        displayOrder = 0,
        statusIds = listOf(UUID.random()),
    )

    private fun sampleStatus() = Status(
        id = UUID.random(),
        name = "Ready",
        category = StatusCategory.TODO,
        colorHex = "#123456",
    )
}
