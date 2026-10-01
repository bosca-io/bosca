package bosca.analytics.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Collector batch containing one context and its pending events. */
@Serializable
data class Events(
    val context: AnalyticsContext,
    val sent: Long,
    @SerialName("sent_micros")
    val sentMicros: Int,
    val events: List<AnalyticsEvent>,
)
