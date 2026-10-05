package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaOutcome
import bosca.workops.model.sla.TaskSlaState
import bosca.workops.model.sla.WorkingCalendar
import bosca.workops.model.task.Task
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.SlaGoalRepository
import bosca.workops.repository.TaskSlaStateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class SlaEvaluatorTest {
    private val states = mockk<TaskSlaStateRepository>(relaxed = true)
    private val goals = mockk<SlaGoalRepository>()
    private val calendars = mockk<WorkingCalendarService>()
    private val projects = mockk<ProjectRepository>()
    private val evaluator = SlaEvaluator(states, goals, calendars, projects)

    @Test
    fun `evaluation applies start pause resume and stop edges in goal order`() = runTest {
        val policyId = UUID.random()
        val task = task()
        val start = goal(policyId, start = "open", pause = null, stop = "done", calendarId = UUID.random())
        val pause = goal(policyId, start = "open", pause = "open", stop = "closed")
        val resume = goal(policyId, start = "open", pause = "done", stop = "closed")
        val stop = goal(policyId, start = "open", pause = null, stop = "open")
        val now = OffsetDateTime.now()
        val open = { goal: SlaGoal, paused: Boolean ->
            TaskSlaState(
                taskId = task.id,
                goalId = goal.id,
                startedAt = now.minusMinutes(10),
                pausedAt = now.takeIf { paused },
                dueAt = now.plusMinutes(50),
                outcome = SlaOutcome.OPEN,
            )
        }
        coEvery { goals.listForPolicy(policyId) } returns listOf(start, pause, resume, stop)
        coEvery { states.get(task.id, start.id) } returns null
        coEvery { states.get(task.id, pause.id) } returns open(pause, false)
        coEvery { states.get(task.id, resume.id) } returns open(resume, true)
        coEvery { states.get(task.id, stop.id) } returns open(stop, false)
        coEvery { calendars.getById(start.calendarId!!) } returns null
        coEvery { calendars.getById(DEFAULT_CALENDAR_ID) } returns alwaysCalendar()

        assertEquals(
            listOf(
                SlaEvaluator.Edge.START,
                SlaEvaluator.Edge.PAUSE,
                SlaEvaluator.Edge.RESUME,
                SlaEvaluator.Edge.STOP_MET,
            ),
            evaluator.evaluate(task, StatusCategory.TODO, policyId),
        )
        coVerify(exactly = 1) { states.start(task.id, start.id, any(), any()) }
        coVerify(exactly = 1) { states.pause(task.id, pause.id) }
        coVerify(exactly = 1) { states.resume(task.id, resume.id) }
        coVerify(exactly = 1) { states.stop(task.id, stop.id, SlaOutcome.MET.name) }
    }

    @Test
    fun `evaluation ignores unmatched and terminal goals and skips persistence without a calendar`() = runTest {
        val policyId = UUID.random()
        val task = task()
        val unmatched = goal(policyId, start = "done", pause = null, stop = "closed")
        val terminal = goal(policyId, start = "open", pause = "open", stop = "done")
        val startWithoutCalendar = goal(policyId, start = "open", pause = null, stop = "closed")
        val now = OffsetDateTime.now()
        coEvery { goals.listForPolicy(policyId) } returns listOf(unmatched, terminal, startWithoutCalendar)
        coEvery { states.get(task.id, unmatched.id) } returns null
        coEvery { states.get(task.id, terminal.id) } returns TaskSlaState(
            task.id,
            terminal.id,
            now.minusMinutes(5),
            dueAt = now.plusMinutes(5),
            outcome = SlaOutcome.MET,
        )
        coEvery { states.get(task.id, startWithoutCalendar.id) } returns null
        coEvery { calendars.getById(DEFAULT_CALENDAR_ID) } returns null

        assertEquals(
            listOf(SlaEvaluator.Edge.START),
            evaluator.evaluate(task, StatusCategory.IN_PROGRESS, policyId),
        )
        coVerify(exactly = 0) { states.start(any(), any(), any(), any()) }
        coVerify(exactly = 0) { states.pause(any(), any()) }
        coVerify(exactly = 0) { states.resume(any(), any()) }
        coVerify(exactly = 0) { states.stop(any(), any(), any()) }
    }

    @Test
    fun `cancelled status matches closed stop but not resolved`() = runTest {
        val policyId = UUID.random()
        val task = task()
        val closed = goal(policyId, start = "open", pause = null, stop = "  CLOSED  ")
        val resolved = goal(policyId, start = "open", pause = null, stop = "resolved")
        val now = OffsetDateTime.now()
        val state = { goal: SlaGoal ->
            TaskSlaState(task.id, goal.id, now.minusMinutes(1), dueAt = now.plusMinutes(1))
        }
        coEvery { goals.listForPolicy(policyId) } returns listOf(closed, resolved)
        coEvery { states.get(task.id, closed.id) } returns state(closed)
        coEvery { states.get(task.id, resolved.id) } returns state(resolved)

        assertEquals(listOf(SlaEvaluator.Edge.STOP_MET), evaluator.evaluate(task, StatusCategory.CANCELLED, policyId))
        coVerify(exactly = 1) { states.stop(task.id, closed.id, SlaOutcome.MET.name) }
        coVerify(exactly = 0) { states.stop(task.id, resolved.id, any()) }
    }

    @Test
    fun `pause conditions that already match state fall through to stop evaluation`() = runTest {
        val policyId = UUID.random()
        val task = task()
        val alreadyPaused = goal(policyId, start = "open", pause = "open", stop = "open")
        val stillRunning = goal(policyId, start = "open", pause = "done", stop = "open")
        val now = OffsetDateTime.now()
        coEvery { goals.listForPolicy(policyId) } returns listOf(alreadyPaused, stillRunning)
        coEvery { states.get(task.id, alreadyPaused.id) } returns TaskSlaState(
            task.id,
            alreadyPaused.id,
            now.minusMinutes(10),
            pausedAt = now.minusMinutes(5),
            dueAt = now.plusMinutes(50),
        )
        coEvery { states.get(task.id, stillRunning.id) } returns TaskSlaState(
            task.id,
            stillRunning.id,
            now.minusMinutes(10),
            dueAt = now.plusMinutes(50),
        )

        assertEquals(
            listOf(SlaEvaluator.Edge.STOP_MET, SlaEvaluator.Edge.STOP_MET),
            evaluator.evaluate(task, StatusCategory.TODO, policyId),
        )
        coVerify(exactly = 0) { states.pause(any(), any()) }
        coVerify(exactly = 0) { states.resume(any(), any()) }
        coVerify(exactly = 1) { states.stop(task.id, alreadyPaused.id, SlaOutcome.MET.name) }
        coVerify(exactly = 1) { states.stop(task.id, stillRunning.id, SlaOutcome.MET.name) }
    }

    private fun goal(
        policyId: UUID,
        start: String,
        pause: String?,
        stop: String,
        calendarId: UUID? = null,
    ) = SlaGoal(
        id = UUID.random(),
        policyId = policyId,
        name = "Goal",
        startConditions = start,
        pauseConditions = pause,
        stopConditions = stop,
        targetMinutes = 60,
        calendarId = calendarId,
    )

    private fun task() = Task(
        id = UUID.random(),
        key = "WORK-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Work",
        reporterProfileId = UUID.random(),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun alwaysCalendar() = WorkingCalendar(
        id = DEFAULT_CALENDAR_ID,
        name = "Always",
        timeZone = "UTC",
        weeklyHours = buildJsonObject {
            java.time.DayOfWeek.entries.forEach { day ->
                put(day.name, buildJsonArray {
                    add(buildJsonObject {
                        put("startLocal", "00:00")
                        put("endLocal", "24:00")
                    })
                })
            }
        },
        holidays = buildJsonArray { },
    )

    private companion object {
        val DEFAULT_CALENDAR_ID: UUID = UUID.parse("c0000000-0000-0000-0000-000000000001")
    }
}
