package bosca.analytics.instrumentation

import bosca.analytics.api.ContentElement
import bosca.analytics.api.Page

/** Context inherited by automatically instrumented work and Compose descendants. */
data class AnalyticsInstrumentationContext(
    val page: Page? = null,
    val elementId: String? = null,
    val elementType: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val content: List<ContentElement> = emptyList(),
) {
    /** Returns this context with [child] values layered over it. */
    fun merge(child: AnalyticsInstrumentationContext): AnalyticsInstrumentationContext = AnalyticsInstrumentationContext(
        page = child.page ?: page,
        elementId = child.elementId ?: elementId,
        elementType = child.elementType ?: elementType,
        extras = extras + child.extras,
        content = content + child.content,
    )

    companion object {
        val Empty = AnalyticsInstrumentationContext()
    }
}
