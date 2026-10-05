package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.worklog.WorkLog
import bosca.workops.model.worklog.WorkLogInput
import bosca.workops.model.worklog.WorklogEstimateAdjustmentMode

interface WorkLogService : Service {
    suspend fun listForTask(taskId: UUID, offset: Long, limit: Int): List<WorkLog>
    suspend fun get(id: UUID): WorkLog?
    suspend fun log(taskId: UUID, profileId: UUID, input: WorkLogInput): WorkLog
    suspend fun update(id: UUID, input: WorkLogInput): WorkLog
    suspend fun delete(id: UUID): Boolean
    suspend fun rollupEpic(epicId: UUID)
}

/**
 * Bridge to project's per-project mode without dragging the
 * full ProjectService surface into this file.
 */
interface WorklogModeResolver : Service {
    suspend fun modeFor(projectId: UUID): WorklogEstimateAdjustmentMode
}
