package bosca.workops.service

import bosca.serialization.UUID
import bosca.di.asProvider
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.SprintAlreadyActiveException
import bosca.workops.model.SprintCloseUnfinishedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.board.Board
import bosca.workops.model.board.BoardProject
import bosca.workops.model.board.BoardType
import bosca.workops.model.board.SwimlaneStrategy
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CloseSprintDestination
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.Sprint
import bosca.workops.model.sprint.SprintState
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.dispatch
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.SprintRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SprintServiceTest {
    private val principalId = UUID.random()
    private val profileId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        mockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        coEvery { any<NotificationDeliveryRequested>().dispatch() } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        unmockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
    }

    @Test
    fun `lookups delegate with bounded pagination and an empty batch avoids the repository`() = runTest {
        val repository = mockk<SprintRepository>()
        val sprint = sampleSprint()
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.getByIds(listOf(sprint.id)) } returns listOf(sprint)
        coEvery { repository.listByBoard(sprint.boardId, 0, 100) } returns listOf(sprint)
        val service = service(repository)

        assertEquals(sprint, service.getById(sprint.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(sprint), service.getByIds(listOf(sprint.id)))
        assertEquals(listOf(sprint), service.listByBoard(sprint.boardId, -4, 101))
        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
    }

    @Test
    fun `create rejects a blank name before adding a sprint`() = runTest {
        val repository = mockk<SprintRepository>()

        val failure = assertFailsWith<WorkOpsValidationException> {
            service(repository).create(CreateSprintInput(UUID.random(), "   "))
        }

        assertEquals("name", failure.field)
        coVerify(exactly = 0) { repository.add(any(), any(), any()) }
    }

    @Test
    fun `start reports missing and stale sprints with typed failures`() = runTest {
        val repository = mockk<SprintRepository>()
        val missingId = UUID.random()
        val stale = sampleSprint()
        coEvery { repository.getById(missingId) } returns null
        coEvery { repository.getById(stale.id) } returns stale
        coEvery { repository.findActive(stale.boardId) } returns stale
        coEvery { repository.start(any()) } returns null
        val service = service(repository)

        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.start(StartSprintInput(missingId, 1), principalId, profileId)
        }
        val optimisticLock = assertFailsWith<OptimisticLockFailedException> {
            service.start(StartSprintInput(stale.id, 4), principalId, profileId)
        }

        assertEquals("Sprint", missing.type)
        assertEquals(missingId.toString(), missing.handle)
        assertEquals("Sprint", optimisticLock.type)
        assertEquals(stale.id, optimisticLock.id)
    }

    @Test
    fun `start preserves the requested dates tasks and expected version`() = runTest {
        val repository = mockk<SprintRepository>()
        val sprint = sampleSprint()
        val startDate = OffsetDateTime.parse("2026-07-21T09:00:00-05:00")
        val endDate = OffsetDateTime.parse("2026-08-04T09:00:00-05:00")
        val committedTaskIds = listOf(UUID.random(), UUID.random())
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.findActive(sprint.boardId) } returns null
        coEvery { repository.start(any()) } coAnswers {
            val staged = firstArg<Sprint>()
            assertEquals(startDate, staged.startDate)
            assertEquals(endDate, staged.endDate)
            assertEquals(committedTaskIds, staged.committedTaskIds)
            assertEquals(7, staged.version)
            staged
        }

        val started = service(repository).start(
            StartSprintInput(
                sprintId = sprint.id,
                expectedVersion = 7,
                startDate = startDate,
                endDate = endDate,
                committedTaskIds = committedTaskIds,
            ),
            principalId, profileId,
        )

        assertEquals(startDate, started.startDate)
    }

    @Test
    fun `close reports missing and stale sprints with typed failures`() = runTest {
        val repository = mockk<SprintRepository>()
        val missingId = UUID.random()
        val stale = sampleSprint()
        coEvery { repository.getById(missingId) } returns null
        coEvery { repository.getById(stale.id) } returns stale
        coEvery { repository.close(stale.id, 13.0, 5) } returns null
        val service = service(repository)

        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.close(CloseSprintInput(missingId, 1), principalId, profileId)
        }
        val optimisticLock = assertFailsWith<OptimisticLockFailedException> {
            service.close(CloseSprintInput(stale.id, 5, velocityPoints = 13.0), principalId, profileId)
        }

        assertEquals("Sprint", missing.type)
        assertEquals(missingId.toString(), missing.handle)
        assertEquals("Sprint", optimisticLock.type)
        assertEquals(stale.id, optimisticLock.id)
    }

    @Test
    fun `task membership follows sprint state and missing sprints remain absent`() = runTest {
        val repository = mockk<SprintRepository>()
        val future = sampleSprint().copy(state = SprintState.FUTURE)
        val active = sampleSprint().copy(state = SprintState.ACTIVE)
        val closed = sampleSprint().copy(state = SprintState.CLOSED)
        val missingId = UUID.random()
        val taskId = UUID.random()
        coEvery { repository.getById(future.id) } returns future
        coEvery { repository.getById(active.id) } returns active
        coEvery { repository.getById(closed.id) } returns closed
        coEvery { repository.getById(missingId) } returns null
        coEvery { repository.addCommittedTask(future.id, taskId) } returns future.copy(committedTaskIds = listOf(taskId))
        coEvery { repository.addDuringSprintTask(active.id, taskId) } returns
            active.copy(addedDuringSprintTaskIds = listOf(taskId))
        coEvery { repository.removeTask(active.id, taskId) } returns active
        val service = service(repository)

        assertEquals(listOf(taskId), service.addTask(future.id, taskId)?.committedTaskIds)
        assertEquals(listOf(taskId), service.addTask(active.id, taskId)?.addedDuringSprintTaskIds)
        assertFailsWith<WorkOpsValidationException> { service.addTask(closed.id, taskId) }
        assertEquals(null, service.addTask(missingId, taskId))
        assertEquals(active, service.removeTask(active.id, taskId))

        coVerify(exactly = 0) { repository.addCommittedTask(closed.id, taskId) }
        coVerify(exactly = 0) { repository.addDuringSprintTask(closed.id, taskId) }
    }

    @Test
    fun `assignment that races sprint start joins the active sprint snapshot`() = runTest {
        val repository = mockk<SprintRepository>()
        val future = sampleSprint()
        val active = future.copy(state = SprintState.ACTIVE, version = future.version + 1)
        val taskId = UUID.random()
        val assigned = active.copy(addedDuringSprintTaskIds = listOf(taskId))
        coEvery { repository.getById(future.id) } returnsMany listOf(future, active)
        coEvery { repository.addCommittedTask(future.id, taskId) } returns null
        coEvery { repository.addDuringSprintTask(active.id, taskId) } returns assigned

        assertEquals(assigned, service(repository).addTask(future.id, taskId))
        coVerify(exactly = 1) { repository.addCommittedTask(future.id, taskId) }
        coVerify(exactly = 1) { repository.addDuringSprintTask(active.id, taskId) }
    }

    @Test
    fun `assignment observes a concurrent close and cannot reuse historical membership`() = runTest {
        val repository = mockk<SprintRepository>()
        val taskId = UUID.random()
        val active = sampleSprint().copy(state = SprintState.ACTIVE)
        val closed = active.copy(state = SprintState.CLOSED, committedTaskIds = listOf(taskId))
        coEvery { repository.getById(active.id) } returnsMany listOf(active, closed)
        coEvery { repository.addDuringSprintTask(active.id, taskId) } returns null

        assertFailsWith<WorkOpsValidationException> { service(repository).addTask(active.id, taskId) }
        coVerify(exactly = 1) { repository.addDuringSprintTask(active.id, taskId) }
    }

    @Test
    fun `membership is idempotent including a concurrent addition`() = runTest {
        val repository = mockk<SprintRepository>()
        val taskId = UUID.random()
        val future = sampleSprint().copy(committedTaskIds = listOf(taskId))
        val active = sampleSprint().copy(state = SprintState.ACTIVE, addedDuringSprintTaskIds = listOf(taskId))
        val racing = sampleSprint().copy(state = SprintState.ACTIVE)
        val assigned = racing.copy(addedDuringSprintTaskIds = listOf(taskId))
        coEvery { repository.getById(future.id) } returns future
        coEvery { repository.getById(active.id) } returns active
        coEvery { repository.getById(racing.id) } returnsMany listOf(racing, assigned)
        coEvery { repository.addDuringSprintTask(racing.id, taskId) } returns null
        val service = service(repository)

        assertEquals(future, service.addTask(future.id, taskId))
        assertEquals(active, service.addTask(active.id, taskId))
        assertEquals(assigned, service.addTask(racing.id, taskId))
        coVerify(exactly = 0) { repository.addCommittedTask(future.id, taskId) }
        coVerify(exactly = 0) { repository.addDuringSprintTask(active.id, taskId) }
        coVerify(exactly = 1) { repository.addDuringSprintTask(racing.id, taskId) }
    }

    @Test
    fun `start rejects another active sprint but permits its own active row`() = runTest {
        val repository = mockk<SprintRepository>()
        val sprint = sampleSprint()
        val other = sampleSprint().copy(boardId = sprint.boardId, state = SprintState.ACTIVE)
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.findActive(sprint.boardId) } returnsMany listOf(other, sprint)
        coEvery { repository.start(any()) } coAnswers { firstArg<Sprint>().copy(state = SprintState.ACTIVE) }
        val boardService = mockk<BoardService>()
        coEvery { boardService.getById(sprint.boardId) } returns null
        val service = service(repository, boardService = boardService)

        val conflict = assertFailsWith<SprintAlreadyActiveException> {
            service.start(StartSprintInput(sprint.id, sprint.version), principalId, profileId)
        }
        assertEquals(sprint.boardId, conflict.boardId)

        val started = service.start(StartSprintInput(sprint.id, sprint.version), principalId, profileId)
        assertEquals(SprintState.ACTIVE, started.state)
        assertTrue(started.startDate != null)
    }

    @Test
    fun `close requires a destination for every sprint task then dispatches the close event`() = runTest {
        val repository = mockk<SprintRepository>()
        val boardService = mockk<BoardService>()
        val projectService = mockk<ProjectService>()
        val projectId = UUID.random()
        val firstTask = UUID.random()
        val secondTask = UUID.random()
        val sprint = sampleSprint().copy(
            state = SprintState.ACTIVE,
            committedTaskIds = listOf(firstTask),
            addedDuringSprintTaskIds = listOf(secondTask),
        )
        val board = board(sprint.boardId, projectId = projectId)
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.close(sprint.id, 8.0, sprint.version) } returns sprint.copy(state = SprintState.CLOSED)
        coEvery { boardService.getById(sprint.boardId) } returns board
        val service = service(repository, boardService, projectService)

        val unfinished = assertFailsWith<SprintCloseUnfinishedException> {
            service.close(
                CloseSprintInput(
                    sprint.id,
                    sprint.version,
                    destinations = listOf(CloseSprintDestination(firstTask)),
                ),
                principalId, profileId,
            )
        }
        assertEquals(setOf(secondTask), unfinished.unfinishedTaskIds.toSet())

        val closed = service.close(
            CloseSprintInput(
                sprint.id,
                sprint.version,
                destinations = listOf(CloseSprintDestination(firstTask), CloseSprintDestination(secondTask)),
                velocityPoints = 8.0,
            ),
            principalId, profileId,
        )
        assertEquals(SprintState.CLOSED, closed.state)
        coVerify(exactly = 1) {
            match<NotificationDeliveryRequested> {
                it.delivery.event == NotificationEvent.SPRINT_CLOSED && it.delivery.projectId == projectId
            }.dispatch()
        }
    }

    @Test
    fun `sprint notifications expand program portfolio and explicit board scopes with pagination and deduplication`() = runTest {
        val repository = mockk<SprintRepository>()
        val boardService = mockk<BoardService>()
        val projectService = mockk<ProjectService>()
        val programService = mockk<ProgramService>()
        val programId = UUID.random()
        val portfolioId = UUID.random()
        val portfolioProgramId = UUID.random()
        val portfolioPrograms = (1..101).map { index -> program(portfolioId, if (index == 101) portfolioProgramId else UUID.random()) }
        val programSprint = sampleSprint()
        val portfolioSprint = sampleSprint()
        val explicitSprint = sampleSprint()
        val programBoard = board(programSprint.boardId, programId = programId)
        val portfolioBoard = board(portfolioSprint.boardId, portfolioId = portfolioId)
        val explicitBoard = board(explicitSprint.boardId)
        val pagedProjects = (1..101).map { index -> project(programId, "P$index") }
        val portfolioProject = project(portfolioProgramId, "PORT")
        val explicitProjectId = UUID.random()
        for (sprint in listOf(programSprint, portfolioSprint, explicitSprint)) {
            coEvery { repository.getById(sprint.id) } returns sprint
            coEvery { repository.findActive(sprint.boardId) } returns null
            coEvery { repository.start(any()) } coAnswers { firstArg<Sprint>().copy(state = SprintState.ACTIVE) }
        }
        coEvery { boardService.getById(programSprint.boardId) } returns programBoard
        coEvery { boardService.getById(portfolioSprint.boardId) } returns portfolioBoard
        coEvery { boardService.getById(explicitSprint.boardId) } returns explicitBoard
        coEvery { projectService.listByProgram(programId, 0, 100) } returns pagedProjects.take(100)
        coEvery { projectService.listByProgram(programId, 100, 100) } returns pagedProjects.drop(100)
        coEvery { programService.listByPortfolio(portfolioId, 0, 100) } returns portfolioPrograms.take(100)
        coEvery { programService.listByPortfolio(portfolioId, 100, 100) } returns portfolioPrograms.drop(100)
        for (program in portfolioPrograms.dropLast(1)) coEvery { projectService.listByProgram(program.id, 0, 100) } returns emptyList()
        coEvery { projectService.listByProgram(portfolioProgramId, 0, 100) } returns listOf(portfolioProject)
        coEvery { boardService.listBoardProjects(explicitBoard.id) } returns listOf(
            BoardProject(explicitBoard.id, explicitProjectId),
            BoardProject(explicitBoard.id, explicitProjectId),
        )
        val service = service(repository, boardService, projectService, programService)

        service.start(StartSprintInput(programSprint.id, programSprint.version), principalId, profileId)
        service.start(StartSprintInput(portfolioSprint.id, portfolioSprint.version), principalId, profileId)
        service.start(StartSprintInput(explicitSprint.id, explicitSprint.version), principalId, profileId)

        coVerify(exactly = 1) { projectService.listByProgram(programId, 100, 100) }
        coVerify(exactly = 1) { programService.listByPortfolio(portfolioId, 0, 100) }
        coVerify(exactly = 1) { programService.listByPortfolio(portfolioId, 100, 100) }
        coVerify(exactly = 1) {
            match<NotificationDeliveryRequested> {
                it.delivery.event == NotificationEvent.SPRINT_STARTED && it.delivery.projectId == explicitProjectId
            }.dispatch()
        }
        coVerify(exactly = 1) {
            match<NotificationDeliveryRequested> {
                it.delivery.event == NotificationEvent.SPRINT_STARTED && it.delivery.projectId == portfolioProject.id
            }.dispatch()
        }
    }

    @Test fun `start rejects an unavailable selected task before writing the sprint`() = runTest {
        val repository = mockk<SprintRepository>()
        val sprint = sampleSprint(); val missingId = UUID.random(); val tasks = taskServiceMock()
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.findActive(sprint.boardId) } returns null
        coEvery { tasks.getByIds(listOf(missingId)) } returns emptyList()
        val failure = assertFailsWith<WorkOpsNotFoundException> {
            service(repository, taskService = tasks).start(StartSprintInput(sprint.id, sprint.version, committedTaskIds = listOf(missingId)), principalId, profileId)
        }
        assertEquals("Task", failure.type)
        val deleted = taskServiceMock().getByIds(listOf(missingId)).single().copy(deletedAt = OffsetDateTime.now())
        coEvery { tasks.getByIds(listOf(missingId)) } returns listOf(deleted)
        assertFailsWith<WorkOpsNotFoundException> {
            service(repository, taskService = tasks).start(StartSprintInput(sprint.id, sprint.version, committedTaskIds = listOf(missingId)), principalId, profileId)
        }
        coVerify(exactly = 0) { repository.start(any()) }
        coVerify(exactly = 0) { tasks.update(any(), any(), any(), any()) }
    }

    @Test fun `invalid close destinations fail before changing the sprint or its tasks`() = runTest {
        val repository = mockk<SprintRepository>(); val taskId = UUID.random(); val targetId = UUID.random()
        val sprint = sampleSprint().copy(state = SprintState.ACTIVE, committedTaskIds = listOf(taskId))
        val tasks = taskServiceMock(); val service = service(repository, taskService = tasks)
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.getById(targetId) } returns null
        val missing = assertFailsWith<WorkOpsNotFoundException> {
            service.close(CloseSprintInput(sprint.id, sprint.version, listOf(CloseSprintDestination(taskId, targetId))), principalId, profileId)
        }
        assertEquals("Sprint", missing.type)
        coEvery { repository.getById(targetId) } returns sampleSprint().copy(id = targetId, state = SprintState.CLOSED)
        for (destinations in listOf(
            listOf(CloseSprintDestination(taskId, targetId)), listOf(CloseSprintDestination(taskId, sprint.id)),
            listOf(CloseSprintDestination(UUID.random())), listOf(CloseSprintDestination(taskId), CloseSprintDestination(taskId)),
        )) assertFailsWith<WorkOpsValidationException> {
            service.close(CloseSprintInput(sprint.id, sprint.version, destinations), principalId, profileId)
        }
        coVerify(exactly = 0) { repository.close(any(), any(), any()) }
        coVerify(exactly = 0) { tasks.update(any(), any(), any(), any()) }
    }

    @Test fun `a stale close never starts task updates`() = runTest {
        val repository = mockk<SprintRepository>(); val taskId = UUID.random()
        val sprint = sampleSprint().copy(state = SprintState.ACTIVE, committedTaskIds = listOf(taskId))
        val tasks = taskServiceMock()
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.close(sprint.id, null, sprint.version) } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service(repository, taskService = tasks).close(CloseSprintInput(sprint.id, sprint.version, listOf(CloseSprintDestination(taskId))), principalId, profileId)
        }
        coVerify(exactly = 0) { tasks.update(any(), any(), any(), any()) }
    }

    @Test fun `starting a sprint preserves previously planned tasks now assigned elsewhere`() = runTest {
        val repository = mockk<SprintRepository>()
        val taskId = UUID.random()
        val sprint = sampleSprint().copy(committedTaskIds = listOf(taskId))
        val tasks = taskServiceMock()
        val movedTask = tasks.getByIds(listOf(taskId)).single().copy(sprintId = UUID.random())
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { repository.findActive(sprint.boardId) } returns null
        coEvery { repository.start(any()) } coAnswers { firstArg<Sprint>().copy(state = SprintState.ACTIVE) }
        coEvery { tasks.getByIds(listOf(taskId)) } returns listOf(movedTask)

        service(repository, taskService = tasks).start(StartSprintInput(sprint.id, sprint.version), principalId, profileId)

        coVerify(exactly = 0) { tasks.update(any(), any(), any(), any()) }
    }

    @Test fun `closing with an unavailable task status fails before any writes`() = runTest {
        val repository = mockk<SprintRepository>()
        val taskId = UUID.random()
        val sprint = sampleSprint().copy(state = SprintState.ACTIVE, committedTaskIds = listOf(taskId))
        val tasks = taskServiceMock()
        val statuses = statusServiceMock()
        coEvery { repository.getById(sprint.id) } returns sprint
        coEvery { statuses.getByIds(any()) } returns emptyList()

        val failure = assertFailsWith<IllegalArgumentException> {
            service(repository, taskService = tasks, statusService = statuses).close(
                CloseSprintInput(sprint.id, sprint.version, listOf(CloseSprintDestination(taskId))), principalId, profileId)
        }

        assertEquals("Task status is unavailable.", failure.message)
        coVerify(exactly = 0) { repository.close(any(), any(), any()) }
        coVerify(exactly = 0) { tasks.update(any(), any(), any(), any()) }
    }

    private fun sampleSprint() = Sprint(
        id = UUID.random(),
        boardId = UUID.random(),
        name = "Sprint 8",
        version = 3,
    )

    private fun board(
        id: UUID,
        projectId: UUID? = null,
        programId: UUID? = null,
        portfolioId: UUID? = null,
    ) = Board(
        id = id,
        projectId = projectId,
        programId = programId,
        portfolioId = portfolioId,
        name = "Delivery",
        type = BoardType.SCRUM,
        swimlaneStrategy = SwimlaneStrategy.NONE,
    )

    private fun project(programId: UUID, key: String) = Project(
        id = UUID.random(),
        programId = programId,
        key = key,
        name = key,
        ownerProfileId = UUID.random(),
    )

    private fun program(portfolioId: UUID, id: UUID) = Program(
        id = id,
        portfolioId = portfolioId,
        key = "PORT",
        name = "Portfolio program",
        ownerProfileId = UUID.random(),
    )

    private fun service(
        repository: SprintRepository,
        boardService: BoardService = mockk(relaxed = true),
        projectService: ProjectService = mockk(relaxed = true),
        programService: ProgramService = mockk(relaxed = true),
        taskService: TaskService = taskServiceMock(),
        statusService: StatusService = statusServiceMock(),
    ) = SprintServiceImpl(
        repository,
        boardService,
        projectService,
        programService,
        taskService.asProvider(),
        statusService,
    )

    private fun taskServiceMock(): TaskService = mockk<TaskService>(relaxed = true).also { service ->
        coEvery { service.getByIds(any()) } coAnswers { firstArg<List<UUID>>().map { id -> Task(
            id = id, key = "PLAN-1", projectId = UUID.random(), taskTypeId = UUID.random(), statusId = UUID.NIL,
            priorityId = UUID.random(), summary = "Sprint work", reporterProfileId = profileId,
            createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
        ) } }
    }
    private fun statusServiceMock(): StatusService = mockk<StatusService>(relaxed = true).also { service ->
        coEvery { service.getByIds(any()) } coAnswers { firstArg<List<UUID>>().map { id -> Status(
            id = id, name = "To do", category = StatusCategory.TODO, colorHex = "#334455",
        ) } }
    }
}
