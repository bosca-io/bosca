package bosca.analytics.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Structured failure information attached to an [AnalyticsEventType.ERROR] event. */
@Serializable
data class ErrorInfo(
    val message: String,
    val type: String? = null,
    @SerialName("stack_trace")
    val stackTrace: String? = null,
    val fatal: Boolean = false,
    val code: String? = null,
)
