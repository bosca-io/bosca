package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Lifecycle state shared by pipeline runs, jobs, and steps.
 */
@DbMapper(PipelineRunStatusMapper::class)
@Serializable
enum class PipelineRunStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILURE,
    CANCELLED,
    SKIPPED
}

object PipelineRunStatusMapper : EnumMapper<PipelineRunStatus>({ PipelineRunStatus.valueOf(it.uppercase()) })
