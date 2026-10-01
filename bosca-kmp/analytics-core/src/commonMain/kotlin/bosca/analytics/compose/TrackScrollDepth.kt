package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.AnalyticsService
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext
import kotlinx.coroutines.flow.collect

@NonRestartableComposable
@Composable
internal fun TrackScrollDepth(
    id: String,
    axis: String,
    source: String,
    context: AnalyticsInstrumentationContext,
    percent: () -> Int,
) {
    val analytics = LocalAnalytics.current
    val tracker = remember(analytics, id, context) { ScrollDepthTracker() }
    val currentPercent = rememberUpdatedState(percent)
    LaunchedEffect(analytics, id, context) {
        snapshotFlow { currentPercent.value() }.collect { current ->
            tracker.update(current).forEach { mark ->
                recordScroll(analytics, id, "scroll_depth", axis, mark, source, context)
            }
        }
    }
    DisposableEffect(analytics, id, context) {
        onDispose {
            if (tracker.maximum > 0) {
                recordScroll(analytics, id, "scroll_max_depth", axis, tracker.maximum, source, context)
            }
        }
    }
}

private fun recordScroll(
    analytics: AnalyticsService,
    id: String,
    type: String,
    axis: String,
    percent: Int,
    source: String,
    context: AnalyticsInstrumentationContext,
) {
    analytics.recordAutomaticEvent(
        AnalyticsEventInput(
            type = AnalyticsEventType.INTERACTION,
            element = AnalyticsElement(
                id = context.elementId ?: id,
                // Keep the measurement identifiable so analytics can use its depth as a quality signal
                // without treating each emitted milestone as another discrete engagement.
                type = type,
                content = context.content,
                extras = context.extras +
                    context.elementType?.let { mapOf("target_element_type" to it) }.orEmpty() +
                    mapOf(
                        "depth_percent" to percent.toString(),
                        "axis" to axis,
                        "instrumentation" to "compose",
                        "source" to source,
                    ),
            ),
            page = context.page,
        ),
    )
}
