package bosca.scheduler.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(ScheduleExecutionStatusMapper::class)
@Serializable
enum class ScheduleExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED,
    STALE
}

object ScheduleExecutionStatusMapper : EnumMapper<ScheduleExecutionStatus>({ ScheduleExecutionStatus.valueOf(it.uppercase()) })