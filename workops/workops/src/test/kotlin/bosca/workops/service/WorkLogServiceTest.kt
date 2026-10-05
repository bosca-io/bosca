@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.dispatch
import bosca.workops.model.task.Task
import bosca.workops.model.worklog.WorkLog
import bosca.workops.model.worklog.WorkLogInput
import bosca.workops.model.worklog.WorklogEstimateAdjustmentMode
import bosca.workops.model.worklog.WorklogVisibility
import bosca.workops.repository.EpicRollup
import bosca.workops.repository.TaskTimeRepository
import bosca.workops.repository.WorkLogInsertParams
import bosca.workops.repository.WorkLogRepository
import bosca.workops.repository.WorkLogUpdateParams
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkLogServiceTest {

    private val repository = mockk<WorkLogRepository>()
    private val tasks = mockk<TaskService>()
    private val taskTimes = mockk<TaskTimeRepository>(relaxed = true)
    private val modeResolver = mockk<WorklogModeResolver>()
    private val service = WorkLogServiceImpl(repository, tasks, taskTimes, modeResolver)
    private val taskId = UUID.random()
    private val profileId = UUID.random()
    private val started = OffsetDateTime.parse("2026-08-19T12:00:00Z")

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        mockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        coEvery { any<NotificationDeliveryRequested>().dispatch() } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `reads clamp pages and log maps input totals and notification identity`() = runTest {
        val task = task(remaining = 10_000)
        val added = workLog(seconds = 5_400)
        val insert = slot<WorkLogInsertParams>()
        coEvery { repository.listForTask(taskId, 0, 200) } returns listOf(added)
        coEvery { repository.getById(added.id) } returns added
        coEvery { tasks.getById(taskId) } returns task
        coEvery { repository.add(capture(insert)) } returns added
        coEvery { repository.sumForTask(taskId) } returns 5_400
        coEvery { modeResolver.modeFor(task.projectId) } returns WorklogEstimateAdjustmentMode.AUTO_REDUCE

        assertEquals(listOf(added), service.listForTask(taskId, -1, 999))
        assertSame(added, service.get(added.id))
        assertSame(
            added,
            service.log(
                taskId,
                profileId,
                WorkLogInput("1h 30m", started, "Worked", WorklogVisibility.ROLE),
            ),
        )
        assertEquals(5_400, insert.captured.timeSpentSeconds)
        assertEquals("ROLE", insert.captured.worklogVisibility)
        assertEquals("Worked", insert.captured.comment)
        coVerify { taskTimes.setTotals(taskId, 5_400, 4_600) }
        coVerify(exactly = 1) { any<NotificationDeliveryRequested>().dispatch() }

        coEvery { tasks.getById(taskId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.log(taskId, profileId, WorkLogInput("1h", started))
        }
    }

    @Test
    fun `estimate adjustment modes cover null absent authored and clamped values`() = runTest {
        var currentTask = task(remaining = 10_000)
        var mode = WorklogEstimateAdjustmentMode.LEAVE_ESTIMATE
        var id = 0
        coEvery { tasks.getById(taskId) } answers { currentTask }
        coEvery { repository.add(any()) } answers { workLog(UUID.fromLongs(0, (++id).toLong()), 3_600) }
        coEvery { repository.sumForTask(taskId) } returns 3_600
        coEvery { modeResolver.modeFor(any()) } answers { mode }

        suspend fun log(adjustment: String?): Long? {
            service.log(taskId, profileId, WorkLogInput("1h", started, estimateAdjustment = adjustment))
            val remaining = slot<Long?>()
            coVerify(atLeast = 1) { taskTimes.setTotals(taskId, 3_600, captureNullable(remaining)) }
            return remaining.captured
        }

        assertEquals(10_000, log(null))
        mode = WorklogEstimateAdjustmentMode.SET_TO_NEW_VALUE
        assertEquals(10_000, log(null))
        assertEquals(7_200, log("2h"))
        mode = WorklogEstimateAdjustmentMode.REDUCE_BY_VALUE
        assertEquals(10_000, log(null))
        assertEquals(0, log("4h"))
        mode = WorklogEstimateAdjustmentMode.AUTO_REDUCE
        assertEquals(6_400, log(null))

        currentTask = task(remaining = null)
        assertEquals(null, log(null))
    }

    @Test
    fun `update validates both rows applies net delta rolls up epic and dispatches`() = runTest {
        val id = UUID.random()
        val epicId = UUID.random()
        val existing = workLog(id, 3_600)
        val task = task(remaining = 10_000, epicId = epicId)
        val updated = existing.copy(timeSpentSeconds = 7_200)
        val params = slot<WorkLogUpdateParams>()

        coEvery { repository.getById(id) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(id, WorkLogInput("2h", started))
        }
        coEvery { repository.getById(id) } returns existing
        coEvery { tasks.getById(taskId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(id, WorkLogInput("2h", started))
        }
        coEvery { tasks.getById(taskId) } returns task
        coEvery { repository.update(any()) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(id, WorkLogInput("2h", started))
        }

        coEvery { repository.update(capture(params)) } returns updated
        coEvery { repository.sumForTask(taskId) } returns 7_200
        coEvery { modeResolver.modeFor(task.projectId) } returns WorklogEstimateAdjustmentMode.AUTO_REDUCE
        val rollup = EpicRollup(20_000, 12_000, 8_000, 3, 1)
        coEvery { taskTimes.aggregateForEpic(epicId) } returns rollup

        assertSame(updated, service.update(id, WorkLogInput("2h", started, "More", WorklogVisibility.PROFILES)))
        assertEquals(7_200, params.captured.timeSpentSeconds)
        assertEquals("PROFILES", params.captured.worklogVisibility)
        coVerify { taskTimes.setTotals(taskId, 7_200, 6_400) }
        coVerify { taskTimes.setEpicRollup(epicId, 20_000, 12_000, 8_000, 3, 1) }
        coVerify(exactly = 1) { any<NotificationDeliveryRequested>().dispatch() }
    }

    @Test
    fun `delete distinguishes missing log missing task and recomputed task with optional epic`() = runTest {
        val id = UUID.random()
        coEvery { repository.softDelete(id) } returns null
        assertFalse(service.delete(id))

        val existing = workLog(id, 3_600)
        coEvery { repository.softDelete(id) } returns existing
        coEvery { tasks.getById(taskId) } returns null
        assertTrue(service.delete(id))

        val task = task(remaining = 10_000)
        coEvery { tasks.getById(taskId) } returns task
        coEvery { repository.sumForTask(taskId) } returns 0
        coEvery { modeResolver.modeFor(task.projectId) } returns WorklogEstimateAdjustmentMode.AUTO_REDUCE
        assertTrue(service.delete(id))
        coVerify { taskTimes.setTotals(taskId, 0, 13_600) }

        val missingAggregateEpic = UUID.random()
        coEvery { taskTimes.aggregateForEpic(missingAggregateEpic) } returns null
        service.rollupEpic(missingAggregateEpic)
        coVerify(exactly = 0) { taskTimes.setEpicRollup(missingAggregateEpic, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `default mode resolver preserves auto reduce until project configuration is surfaced`() = runTest {
        val resolver = WorklogModeResolverImpl(mockk())
        assertEquals(WorklogEstimateAdjustmentMode.AUTO_REDUCE, resolver.modeFor(UUID.random()))
    }

    private fun task(remaining: Long?, epicId: UUID? = null) = Task(
        id = taskId,
        key = "TIME-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Timed task",
        reporterProfileId = profileId,
        remainingEstimateSeconds = remaining,
        epicTaskId = epicId,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun workLog(id: UUID = UUID.random(), seconds: Long) = WorkLog(
        id = id,
        taskId = taskId,
        profileId = profileId,
        timeSpentSeconds = seconds,
        startedAt = started,
    )
}
