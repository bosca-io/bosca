package bosca.scheduler.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(JobHistorySourceMapper::class)
@Serializable
enum class JobHistorySource {
    SCHEDULER,
    EVENT
}

object JobHistorySourceMapper : EnumMapper<JobHistorySource>({ JobHistorySource.valueOf(it.uppercase()) })
