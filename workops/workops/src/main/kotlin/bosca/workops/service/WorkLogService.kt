package bosca.workops.service

import bosca.db.transaction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.dispatch
import bosca.workops.model.worklog.DurationShorthand
import bosca.workops.model.worklog.WorkLog
import bosca.workops.model.worklog.WorkLogInput
import bosca.workops.model.worklog.WorklogEstimateAdjustmentMode
import bosca.workops.model.worklog.WorklogVisibility
import bosca.workops.repository.TaskTimeRepository
import bosca.workops.repository.WorkLogInsertParams
import bosca.workops.repository.WorkLogRepository
import bosca.workops.repository.WorkLogUpdateParams

@ServiceImplementation
class WorklogModeResolverImpl(
    private val projectRepository: bosca.workops.repository.ProjectRepository,
) : WorklogModeResolver {
    override suspend fun modeFor(projectId: UUID): WorklogEstimateAdjustmentMode {
        // Project repo doesn't yet surface the mode column; the
        // simplest path until Phase 11 is a direct query against
        // the column. We fall back to AUTO_REDUCE.
        return WorklogEstimateAdjustmentMode.AUTO_REDUCE
    }
}

@ServiceImplementation
class WorkLogServiceImpl(
    private val repository: WorkLogRepository,
    private val taskService: TaskService,
    private val taskTimeRepository: TaskTimeRepository,
    private val modeResolver: WorklogModeResolver,
) : WorkLogService {

    override suspend fun listForTask(taskId: UUID, offset: Long, limit: Int) =
        repository.listForTask(taskId, offset.coerceAtLeast(0), limit.coerceIn(1, 200))

    override suspend fun get(id: UUID) = repository.getById(id)

    override suspend fun log(taskId: UUID, profileId: UUID, input: WorkLogInput): WorkLog = transaction {
        val task = taskService.getById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        val seconds = DurationShorthand.parseToSeconds(input.timeSpent)
        val log = repository.add(
            WorkLogInsertParams(
                taskId = taskId,
                profileId = profileId,
                timeSpentSeconds = seconds,
                startedAt = input.startedAt,
                comment = input.comment,
                worklogVisibility = input.visibility.name,
            )
        )
        recomputeTaskTotals(taskId, task.projectId, seconds, input.estimateAdjustment, isInsert = true)
        val epicId = task.epicTaskId
        if (epicId != null) rollupEpic(epicId)
        NotificationDeliveryRequested(
            NotificationDelivery(
                event = NotificationEvent.WORKLOG_LOGGED,
                taskId = task.id,
                projectId = task.projectId,
                actorProfileId = profileId,
            ),
        ).dispatch()
        log
    }

    override suspend fun update(id: UUID, input: WorkLogInput): WorkLog = transaction {
        val existing = repository.getById(id)
            ?: throw WorkOpsNotFoundException("WorkLog", id.toString())
        val task = taskService.getById(existing.taskId)
            ?: throw WorkOpsNotFoundException("Task", existing.taskId.toString())
        val newSeconds = DurationShorthand.parseToSeconds(input.timeSpent)
        val updated = repository.update(
            WorkLogUpdateParams(
                id = id,
                timeSpentSeconds = newSeconds,
                startedAt = input.startedAt,
                comment = input.comment,
                worklogVisibility = input.visibility.name,
            )
        ) ?: throw WorkOpsNotFoundException("WorkLog", id.toString())
        // Net delta vs the previous entry — adjust totals using
        // the difference so AUTO_REDUCE doesn't double-deduct.
        val delta = newSeconds - existing.timeSpentSeconds
        recomputeTaskTotals(task.id, task.projectId, delta, input.estimateAdjustment, isInsert = false)
        val epicId = task.epicTaskId
        if (epicId != null) rollupEpic(epicId)
        NotificationDeliveryRequested(
            NotificationDelivery(
                event = NotificationEvent.WORKLOG_UPDATED,
                taskId = task.id,
                projectId = task.projectId,
                actorProfileId = existing.profileId,
            ),
        ).dispatch()
        updated
    }

    override suspend fun delete(id: UUID): Boolean = transaction {
        val existing = repository.softDelete(id) ?: return@transaction false
        val task = taskService.getById(existing.taskId) ?: return@transaction true
        // On delete, time_spent_seconds drops by the deleted row's
        // amount; remaining_estimate_seconds restores by the same
        // amount under AUTO_REDUCE (the original intent).
        recomputeTaskTotals(task.id, task.projectId, -existing.timeSpentSeconds, null, isInsert = false)
        val epicId = task.epicTaskId
        if (epicId != null) rollupEpic(epicId)
        true
    }

    override suspend fun rollupEpic(epicId: UUID) {
        val agg = taskTimeRepository.aggregateForEpic(epicId) ?: return
        taskTimeRepository.setEpicRollup(
            id = epicId,
            totalEstimate = agg.totalEstimate,
            totalRemaining = agg.totalRemaining,
            totalSpent = agg.totalSpent,
            childCount = agg.childCount,
            childDoneCount = agg.childDoneCount,
        )
    }

    /**
     * `delta` is the **change** to `time_spent_seconds`:
     * - insert: +newSeconds
     * - update: newSeconds - oldSeconds
     * - delete: -oldSeconds
     *
     * `estimateOverride` carries the input string for the modes
     * that take a value (`SET_TO_NEW_VALUE`, `REDUCE_BY_VALUE`).
     */
    private suspend fun recomputeTaskTotals(
        taskId: UUID,
        projectId: UUID,
        delta: Long,
        estimateOverride: String?,
        isInsert: Boolean,
    ) {
        val task = taskService.getById(taskId) ?: return
        // Recompute total spent from sum() so concurrent edits
        // converge to the truth instead of accumulating drift.
        val newSpent = repository.sumForTask(taskId)
        val mode = modeResolver.modeFor(projectId)
        val newRemaining = computeRemaining(task.remainingEstimateSeconds, delta, mode, estimateOverride, isInsert)
        taskTimeRepository.setTotals(taskId, newSpent, newRemaining)
    }

    private fun computeRemaining(
        currentRemaining: Long?,
        delta: Long,
        mode: WorklogEstimateAdjustmentMode,
        estimateOverride: String?,
        isInsert: Boolean,
    ): Long? {
        if (currentRemaining == null) return null
        return when (mode) {
            WorklogEstimateAdjustmentMode.AUTO_REDUCE -> (currentRemaining - delta).coerceAtLeast(0L)
            WorklogEstimateAdjustmentMode.LEAVE_ESTIMATE -> currentRemaining
            WorklogEstimateAdjustmentMode.SET_TO_NEW_VALUE -> {
                if (estimateOverride != null) {
                    DurationShorthand.parseToSeconds(estimateOverride)
                } else currentRemaining
            }
            WorklogEstimateAdjustmentMode.REDUCE_BY_VALUE -> {
                if (estimateOverride != null) {
                    val reduceBy = DurationShorthand.parseToSeconds(estimateOverride)
                    (currentRemaining - reduceBy).coerceAtLeast(0L)
                } else currentRemaining
            }
        }
    }
}
