package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * What event caused a pipeline run to be created.
 */
@DbMapper(PipelineTriggerTypeMapper::class)
@Serializable
enum class PipelineTriggerType {
    PUSH,
    PULL_REQUEST,
    TAG,
    MANUAL,
    SCHEDULE,

    /** A release was started — the run carries `release.*` parameters. */
    RELEASE,

    /** A release is being promoted to an environment — adds `promotion.environment`. */
    PROMOTION
}

object PipelineTriggerTypeMapper : EnumMapper<PipelineTriggerType>({ PipelineTriggerType.valueOf(it.uppercase()) })
