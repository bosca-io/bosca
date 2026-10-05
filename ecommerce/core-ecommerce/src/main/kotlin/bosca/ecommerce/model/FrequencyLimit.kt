package bosca.ecommerce.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Serializable

/** The window over which a promotion's per-account redemption limit is counted. */
@Serializable
enum class FrequencyLimit {
    FOREVER, DAILY, WEEKLY, MONTHLY, YEARLY;

    /** The inclusive start of the limit window ending at [now]; FOREVER spans all redemption history. */
    fun windowStart(now: OffsetDateTime): OffsetDateTime = when (this) {
        FOREVER -> now.minusYears(1000)
        DAILY -> now.minusDays(1)
        WEEKLY -> now.minusWeeks(1)
        MONTHLY -> now.minusMonths(1)
        YEARLY -> now.minusYears(1)
    }
}
