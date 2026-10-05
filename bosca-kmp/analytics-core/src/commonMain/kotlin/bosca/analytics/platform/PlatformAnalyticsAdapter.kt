package bosca.analytics.platform

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.AnalyticsService
import bosca.analytics.api.ErrorInfo

/** Adapts client-core's non-suspending analytics contract to [AnalyticsService]. */
internal class PlatformAnalyticsAdapter(
    private val service: AnalyticsService,
) : bosca.core.platform.Analytics {
    override fun logEvent(name: String, params: Map<String, Any>) {
        service.recordAutomaticEvent(
            AnalyticsEventInput(
                type = AnalyticsEventType.INTERACTION,
                element = AnalyticsElement(name, "event", extras = params.toAnalyticsExtras()),
            ),
        )
    }

    override fun logError(
        message: String,
        type: String?,
        stackTrace: String?,
        fatal: Boolean,
        code: String?,
        params: Map<String, Any>,
    ) {
        service.recordAutomaticEvent(
            AnalyticsEventInput(
                type = AnalyticsEventType.ERROR,
                element = AnalyticsElement(type.orEmpty(), "error", extras = params.toAnalyticsExtras()),
                error = ErrorInfo(message, type, stackTrace, fatal, code),
            ),
        )
    }
}

private fun Map<String, Any>.toAnalyticsExtras(): Map<String, String> = mapValues { it.value.toString() }
