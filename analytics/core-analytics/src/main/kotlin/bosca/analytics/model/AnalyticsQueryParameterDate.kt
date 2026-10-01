package bosca.analytics.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class AnalyticsQueryParameterDate(
    @Contextual
    val value: OffsetDateTime? = null,
    val now: Boolean = false,
    val nowDayOffset: Int? = null
)