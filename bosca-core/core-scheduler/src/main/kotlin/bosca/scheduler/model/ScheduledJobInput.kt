package bosca.scheduler.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Input for creating or updating a scheduled job.
 */
@Serializable
data class ScheduledJobInput(
    val name: String,
    val description: String? = null,
    val jobName: String,
    val jobParameters: JsonElement = JsonObject(emptyMap()),
    val cronExpression: String,
    val enabled: Boolean? = true,
    val allowConcurrent: Boolean? = false,
    val catchUp: Boolean? = false,
    val maxCatchUp: Int? = 1,
    /** Whether this job must have a confirmed execution principal before it can be enabled. */
    val requiresPrincipal: Boolean? = null,
)
