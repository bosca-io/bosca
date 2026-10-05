package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaOutcome
import bosca.workops.model.sla.SlaPolicy
import bosca.workops.model.sla.TaskSlaState
import bosca.workops.model.sla.WorkingCalendar
import bosca.workops.model.task.Task
import bosca.workops.repository.TaskSlaStateRepository
import bosca.workops.service.SlaPolicyService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.WorkingCalendarInput
import bosca.workops.service.WorkingCalendarService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlaControllerTest {

    private val calendarService = mockk<WorkingCalendarService>()
    private val policyService = mockk<SlaPolicyService>()
    private val stateRepository = mockk<TaskSlaStateRepository>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val taskService = mockk<TaskService>()
    private val taskPermissions = mockk<TaskPermissionEvaluator>()
    private val authentication = mockk<AuthenticationContext>()

    private fun queryController() = SlaQueryController(
        calendarService = calendarService,
        policyService = policyService,
        stateRepository = stateRepository,
        groupEvaluator = groupEvaluator,
        taskService = taskService,
        taskPermissions = taskPermissions,
    )

    private fun mutationController() = SlaMutationController(
        calendarService = calendarService,
        policyService = policyService,
        groupEvaluator = groupEvaluator,
        json = Json,
    )

    @Test
    fun `working calendar fields are exposed`() {
        val weeklyHours = Json.parseToJsonElement("""{"MONDAY":[]}""")
        val holidays = Json.parseToJsonElement("""["2026-01-01"]""")
        val calendar = WorkingCalendar(
            id = UUID.random(),
            name = "standard",
            description = "Standard support hours",
            timeZone = "America/Chicago",
            weeklyHours = weeklyHours,
            holidays = holidays,
            version = 4,
        )
        val controller = WorkingCalendarTypeController()

        assertEquals(calendar.id, controller.id(calendar))
        assertEquals(calendar.name, controller.name(calendar))
        assertEquals(calendar.description, controller.description(calendar))
        assertEquals(calendar.timeZone, controller.timeZone(calendar))
        assertEquals(weeklyHours, controller.weeklyHours(calendar))
        assertEquals(holidays, controller.holidays(calendar))
        assertEquals(4, controller.version(calendar))
    }

    @Test
    fun `SLA policy fields and goals are exposed`() = runTest {
        val policy = samplePolicy()
        val goals = listOf(sampleGoal(policy.id))
        coEvery { policyService.goalsFor(policy.id) } returns goals
        val controller = SlaPolicyTypeController(policyService)

        assertEquals(policy.id, controller.id(policy))
        assertEquals(policy.name, controller.name(policy))
        assertEquals(policy.description, controller.description(policy))
        assertEquals(policy.version, controller.version(policy))
        assertEquals(goals, controller.goals(policy))
    }

    @Test
    fun `SLA goal fields are exposed`() {
        val goal = sampleGoal()
        val controller = SlaGoalTypeController()

        assertEquals(goal.id, controller.id(goal))
        assertEquals(goal.policyId, controller.policyId(goal))
        assertEquals(goal.name, controller.name(goal))
        assertEquals(goal.startConditions, controller.startConditions(goal))
        assertEquals(goal.pauseConditions, controller.pauseConditions(goal))
        assertEquals(goal.stopConditions, controller.stopConditions(goal))
        assertEquals(goal.targetMinutes, controller.targetMinutes(goal))
        assertEquals(goal.atRiskAtPercent, controller.atRiskAtPercent(goal))
        assertEquals(goal.calendarId, controller.calendarId(goal))
        assertEquals(goal.displayOrder, controller.displayOrder(goal))
    }

    @Test
    fun `SLA goal state fields are exposed`() {
        val startedAt = OffsetDateTime.parse("2026-07-21T10:00:00Z")
        val pausedAt = startedAt.plusMinutes(15)
        val view = SlaGoalStateView(
            taskId = UUID.random(),
            goalId = UUID.random(),
            startedAt = startedAt,
            pausedAt = pausedAt,
            pausedTotalSeconds = 900,
            dueAt = startedAt.plusHours(4),
            outcome = SlaOutcome.OPEN.name,
            atRisk = true,
            breached = false,
        )
        val controller = SlaGoalStateTypeController()

        assertEquals(view.taskId, controller.taskId(view))
        assertEquals(view.goalId, controller.goalId(view))
        assertEquals(startedAt, controller.startedAt(view))
        assertEquals(pausedAt, controller.pausedAt(view))
        assertEquals(900, controller.pausedTotalSeconds(view))
        assertEquals(view.dueAt, controller.dueAt(view))
        assertEquals(SlaOutcome.OPEN.name, controller.outcome(view))
        assertEquals(true, controller.atRisk(view))
        assertEquals(false, controller.breached(view))
    }

    @Test
    fun `admin queries verify access and delegate to services`() = runTest {
        val calendar = sampleCalendar()
        val policy = samplePolicy()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { calendarService.list() } returns listOf(calendar)
        coEvery { calendarService.getById(calendar.id) } returns calendar
        coEvery { policyService.list() } returns listOf(policy)
        coEvery { policyService.getById(policy.id) } returns policy
        val controller = queryController()

        assertEquals(listOf(calendar), controller.workingCalendars(authentication))
        assertEquals(calendar, controller.workingCalendar(authentication, calendar.id))
        assertEquals(listOf(policy), controller.slaPolicies(authentication))
        assertEquals(policy, controller.slaPolicy(authentication, policy.id))
        verify(exactly = 4) { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `admin query stops before service access when access is denied`() = runTest {
        every {
            groupEvaluator.verifyHasAdminGroup(authentication)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            queryController().workingCalendars(authentication)
        }
        coVerify(exactly = 0) { calendarService.list() }
    }

    @Test
    fun `task SLA states require an existing visible task and map repository state`() = runTest {
        val task = sampleTask()
        val startedAt = OffsetDateTime.parse("2026-07-21T10:00:00Z")
        val state = TaskSlaState(
            taskId = task.id,
            goalId = UUID.random(),
            startedAt = startedAt,
            pausedAt = startedAt.plusMinutes(10),
            pausedTotalSeconds = 600,
            dueAt = startedAt.plusHours(2),
            outcome = SlaOutcome.MET,
            atRiskEmitted = true,
            breachEmitted = false,
        )
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.verifyAllowed(authentication, task, PermissionAction.VIEW) } returns Unit
        coEvery { stateRepository.listForTask(task.id) } returns listOf(state)

        val result = queryController().taskSlaStates(authentication, task.id).single()

        assertEquals(state.taskId, result.taskId)
        assertEquals(state.goalId, result.goalId)
        assertEquals(state.startedAt, result.startedAt)
        assertEquals(state.pausedAt, result.pausedAt)
        assertEquals(state.pausedTotalSeconds, result.pausedTotalSeconds)
        assertEquals(state.dueAt, result.dueAt)
        assertEquals(SlaOutcome.MET.name, result.outcome)
        assertEquals(true, result.atRisk)
        assertEquals(false, result.breached)
    }

    @Test
    fun `task SLA states error when task is missing`() = runTest {
        val taskId = UUID.random()
        coEvery { taskService.getById(taskId) } returns null

        assertFailsWith<IllegalStateException> {
            queryController().taskSlaStates(authentication, taskId)
        }
        coVerify(exactly = 0) { stateRepository.listForTask(any()) }
    }

    @Test
    fun `create working calendar verifies admin and converts JSON input`() = runTest {
        val weeklyHours = Json.parseToJsonElement("""{"MONDAY":[]}""")
        val holidays = Json.parseToJsonElement("""["2026-01-01"]""")
        val input = CreateWorkingCalendarInput(
            name = "standard",
            description = "Standard support hours",
            timeZone = "America/Chicago",
            weeklyHours = weeklyHours,
            holidays = holidays,
        )
        val serviceInput = WorkingCalendarInput(
            name = input.name,
            description = input.description,
            timeZone = input.timeZone,
            weeklyHours = """{"MONDAY":[]}""",
            holidays = """["2026-01-01"]""",
        )
        val calendar = sampleCalendar()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { calendarService.create(serviceInput) } returns calendar

        assertEquals(calendar, mutationController().createWorkingCalendar(authentication, input))
        verify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) { calendarService.create(serviceInput) }
    }

    @Test
    fun `create SLA policy verifies admin and delegates`() = runTest {
        val policy = samplePolicy()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { policyService.create(policy.name, policy.description) } returns policy

        assertEquals(
            policy,
            mutationController().createSlaPolicy(authentication, policy.name, policy.description),
        )
        verify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) { policyService.create(policy.name, policy.description) }
    }

    private fun sampleCalendar() = WorkingCalendar(
        id = UUID.random(),
        name = "standard",
        description = "Standard support hours",
        timeZone = "America/Chicago",
    )

    private fun samplePolicy() = SlaPolicy(
        id = UUID.random(),
        name = "support",
        description = "Support response policy",
        version = 2,
    )

    private fun sampleGoal(policyId: UUID = UUID.random()) = SlaGoal(
        id = UUID.random(),
        policyId = policyId,
        name = "first response",
        startConditions = "status = OPEN",
        pauseConditions = "status = WAITING",
        stopConditions = "status = RESOLVED",
        targetMinutes = 240,
        atRiskAtPercent = 75,
        calendarId = UUID.random(),
        displayOrder = 3,
    )

    private fun sampleTask() = Task(
        id = UUID.random(),
        key = "SLA-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "task",
        reporterProfileId = UUID.random(),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )
}
