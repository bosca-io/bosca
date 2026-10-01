package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsEventInput

/** Applies inherited instrumentation metadata without discarding event-specific values. */
internal fun AnalyticsEventInput.withInstrumentationContext(
    context: AnalyticsInstrumentationContext,
): AnalyticsEventInput {
    if (context == AnalyticsInstrumentationContext.Empty) return this
    return copy(
        element = element.copy(
            id = context.elementId ?: element.id,
            type = context.elementType ?: element.type,
            content = context.content + element.content,
            extras = context.extras + element.extras,
        ),
        page = page ?: context.page,
    )
}
