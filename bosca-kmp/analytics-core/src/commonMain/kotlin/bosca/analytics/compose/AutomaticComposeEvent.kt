package bosca.analytics.compose

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.AnalyticsService
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext

internal fun recordComposeInteraction(
    analytics: AnalyticsService,
    context: AnalyticsInstrumentationContext,
    id: String,
    elementType: String,
    source: String,
    extras: Map<String, String> = emptyMap(),
) {
    analytics.recordAutomaticEvent(
        AnalyticsEventInput(
            type = AnalyticsEventType.INTERACTION,
            element = AnalyticsElement(
                id = context.elementId ?: id,
                type = context.elementType ?: elementType,
                content = context.content,
                extras = context.extras + extras + mapOf(
                    "instrumentation" to "compose",
                    "source" to source,
                ),
            ),
            page = context.page,
        ),
    )
}

internal fun recordComposeImpression(
    analytics: AnalyticsService,
    context: AnalyticsInstrumentationContext,
    id: String,
    elementType: String,
    source: String,
    extras: Map<String, String> = emptyMap(),
) {
    analytics.recordAutomaticEvent(
        AnalyticsEventInput(
            type = AnalyticsEventType.IMPRESSION,
            element = AnalyticsElement(
                id = context.elementId ?: id,
                type = context.elementType ?: elementType,
                content = context.content,
                extras = context.extras + extras + mapOf(
                    "instrumentation" to "compose",
                    "source" to source,
                ),
            ),
            page = context.page,
        ),
    )
}
