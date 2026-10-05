@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaOutcome
import bosca.workops.model.sla.TaskSlaState
import bosca.workops.model.task.Task
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.SlaGoalRepository
import bosca.workops.repository.TaskRepository
import bosca.workops.repository.TaskSlaStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [SlaTickServiceImpl.tick] — verifies breach vs at-risk
 * dispatch, debounce flag enforcement, and safe-pass
 * behaviour when no boundary has been crossed.
 */
class SlaTickServiceTest {

    private val stateRepo = mockk<TaskSlaStateRepository>(relaxUnitFun = true)
    private val goalRepo = mockk<SlaGoalRepository>()
    private val taskRepo = mockk<TaskRepository>()
    private val projectRepo = mockk<ProjectRepository>()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var tickService: SlaTickServiceImpl

    private val taskId = UUID.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val goalId = UUID.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val projectId = UUID.parse("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val profileId = UUID.parse("dddddddd-dddd-dddd-dddd-dddddddddddd")
    private val principalId = UUID.parse("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")

    private val sampleTask = Task(
        id = taskId,
        key = "TST-1",
        projectId = projectId,
        taskTypeId = UUID.parse("11111111-1111-1111-1111-111111111111"),
        statusId = UUID.parse("22222222-2222-2222-2222-222222222222"),
        priorityId = UUID.parse("33333333-3333-3333-3333-333333333333"),
        summary = "Sample task for SLA tick test",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private val sampleProject = Project(
        id = projectId,
        programId = UUID.parse("44444444-4444-4444-4444-444444444444"),
        key = "TST",
        name = "Test Project",
        ownerProfileId = profileId,
    )

    private val sampleGoal = SlaGoal(
        id = goalId,
        policyId = UUID.parse("55555555-5555-5555-5555-555555555555"),
        name = "Response time",
        startConditions = "open",
        stopConditions = "done",
        targetMinutes = 100,
        atRiskAtPercent = 80,
    )

    @BeforeTest
    fun setup() {
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        bosca.di.provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        bosca.di.provides<kotlinx.serialization.json.Json>(singleton = true) { kotlinx.serialization.json.Json }
        tickService = SlaTickServiceImpl(
            stateRepo = stateRepo,
            goalRepo = goalRepo,
            taskRepository = taskRepo,
            projectRepository = projectRepo,
            json = json,
        )
        coEvery { taskRepo.getActiveById(taskId) } returns sampleTask
        coEvery { projectRepo.getById(projectId) } returns sampleProject
        coEvery { goalRepo.getById(goalId) } returns sampleGoal
    }

    @Test
    fun `breach notification fires when due_at is in the past`() = runBlocking {
        val now = OffsetDateTime.now()
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(120),
            dueAt = now.minusMinutes(30),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)

        val emitted = tickService.tick(now, atRiskWindowMinutes = 15, limit = 256)

        assertEquals(1, emitted)
        coVerify(exactly = 1) { stateRepo.markBreachEmitted(taskId, goalId) }
    }

    @Test
    fun `at-risk notification fires when elapsed exceeds threshold percentage`() = runBlocking {
        val now = OffsetDateTime.now()
        // 85 minutes elapsed, target 100, atRiskAtPercent 80 => threshold 80 minutes => at risk
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(85),
            dueAt = now.plusMinutes(15),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)

        val emitted = tickService.tick(now, atRiskWindowMinutes = 30, limit = 256)

        assertEquals(1, emitted)
        coVerify(exactly = 1) { stateRepo.markAtRiskEmitted(taskId, goalId) }
    }

    @Test
    fun `breachEmitted flag prevents duplicate breach notification`() = runBlocking {
        val now = OffsetDateTime.now()
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(120),
            dueAt = now.minusMinutes(30),
            outcome = SlaOutcome.OPEN,
            breachEmitted = true,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)

        val emitted = tickService.tick(now, atRiskWindowMinutes = 15, limit = 256)

        // breachEmitted is already true, and dueAt is in the past so the breach
        // branch is evaluated first but skipped. The at-risk branch runs next:
        // elapsed=120, threshold=80 => at-risk would fire since atRiskEmitted=false.
        assertEquals(1, emitted)
        coVerify(exactly = 0) { stateRepo.markBreachEmitted(any(), any()) }
        coVerify(exactly = 1) { stateRepo.markAtRiskEmitted(taskId, goalId) }
    }

    @Test
    fun `atRiskEmitted flag prevents duplicate at-risk notification`() = runBlocking {
        val now = OffsetDateTime.now()
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(85),
            dueAt = now.plusMinutes(15),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = true,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)

        val emitted = tickService.tick(now, atRiskWindowMinutes = 30, limit = 256)

        assertEquals(0, emitted)
        coVerify(exactly = 0) { stateRepo.markAtRiskEmitted(any(), any()) }
        coVerify(exactly = 0) { stateRepo.markBreachEmitted(any(), any()) }
    }

    @Test
    fun `task well within SLA triggers neither notification`() = runBlocking {
        val now = OffsetDateTime.now()
        // 30 minutes elapsed, target 100, atRiskAtPercent 80 => threshold 80 => well within
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(30),
            dueAt = now.plusMinutes(70),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)

        val emitted = tickService.tick(now, atRiskWindowMinutes = 15, limit = 256)

        assertEquals(0, emitted)
        coVerify(exactly = 0) { stateRepo.markBreachEmitted(any(), any()) }
        coVerify(exactly = 0) { stateRepo.markAtRiskEmitted(any(), any()) }
    }

    @Test
    fun `empty candidate list produces zero emissions`() = runBlocking {
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns emptyList()

        val emitted = tickService.tick(OffsetDateTime.now())

        assertEquals(0, emitted)
    }

    @Test
    fun `missing task skips evaluation without failing`() = runBlocking {
        val now = OffsetDateTime.now()
        val orphanTaskId = UUID.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val state = TaskSlaState(
            taskId = orphanTaskId,
            goalId = goalId,
            startedAt = now.minusMinutes(120),
            dueAt = now.minusMinutes(30),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)
        coEvery { taskRepo.getActiveById(orphanTaskId) } returns null

        val emitted = tickService.tick(now)

        assertEquals(0, emitted)
    }

    @Test
    fun `missing project skips evaluation without failing`() = runBlocking {
        val now = OffsetDateTime.now()
        val orphanProjectId = UUID.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val taskWithBadProject = sampleTask.copy(projectId = orphanProjectId)
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(120),
            dueAt = now.minusMinutes(30),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)
        coEvery { taskRepo.getActiveById(taskId) } returns taskWithBadProject
        coEvery { projectRepo.getById(orphanProjectId) } returns null

        val emitted = tickService.tick(now)

        assertEquals(0, emitted)
    }

    @Test
    fun `missing goal skips at-risk evaluation without failing`() = runBlocking {
        val now = OffsetDateTime.now()
        val state = TaskSlaState(
            taskId = taskId,
            goalId = goalId,
            startedAt = now.minusMinutes(85),
            dueAt = now.plusMinutes(15),
            outcome = SlaOutcome.OPEN,
            breachEmitted = false,
            atRiskEmitted = false,
        )
        coEvery { stateRepo.pendingBoundaries(any(), any(), any()) } returns listOf(state)
        coEvery { goalRepo.getById(goalId) } returns null

        assertEquals(0, tickService.tick(now))
        coVerify(exactly = 0) { stateRepo.markAtRiskEmitted(any(), any()) }
        coVerify(exactly = 0) { stateRepo.markBreachEmitted(any(), any()) }
    }
}
