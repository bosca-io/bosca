package bosca.scheduler.model

import bosca.serialization.OffsetDateTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Result of validating a cron expression.
 */
@Serializable
data class CronValidationResult(
    val valid: Boolean,
    val error: String? = null,
    val nextRuns: List<OffsetDateTime> = emptyList()
)
