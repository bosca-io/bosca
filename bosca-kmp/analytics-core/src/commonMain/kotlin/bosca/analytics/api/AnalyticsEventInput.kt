package bosca.analytics.api

/** Input accepted by event factories and the top-level logging API. */
data class AnalyticsEventInput(
    val type: AnalyticsEventType,
    val element: AnalyticsElement,
    val page: Page? = null,
    val error: ErrorInfo? = null,
)
