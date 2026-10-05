package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import kotlin.time.Clock

/** Stable Compose ABI used by analytics-compiler to wrap zero-argument control callbacks. */
@NonRestartableComposable
@Composable
fun rememberAutoInstrumentedAction(
    onAction: () -> Unit,
    id: String,
    elementType: String,
    action: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): () -> Unit {
    val analytics = LocalAnalytics.current
    val context = automaticContext(pagePath, pageTitle)
    val rageClicks = remember(id, action) { RageClickTracker() }
    return remember(analytics, onAction, id, elementType, action, source, context, rageClicks) {
        {
            recordComposeInteraction(
                analytics = analytics,
                context = context,
                id = id,
                elementType = elementType,
                source = source,
                extras = mapOf("action" to action),
            )
            if (action == "click") {
                rageClicks.record(Clock.System.now().toEpochMilliseconds())?.let { count ->
                    recordComposeInteraction(
                        analytics = analytics,
                        context = context.copy(elementType = null),
                        id = id,
                        elementType = "rage_click",
                        source = source,
                        extras = mapOf(
                            "click_count" to count.toString(),
                            "window_ms" to "1000",
                        ),
                    )
                }
            }
            onAction()
        }
    }
}

/** Backward-compatible compiler ABI for click-only instrumentation. */
@NonRestartableComposable
@Composable
fun rememberAutoInstrumentedClick(
    onClick: () -> Unit,
    id: String,
    elementType: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): () -> Unit = rememberAutoInstrumentedAction(
    onAction = onClick,
    id = id,
    elementType = elementType,
    action = "click",
    source = source,
    pagePath = pagePath,
    pageTitle = pageTitle,
)

/** Nullable counterpart used by controls whose disabled state is represented by a null callback. */
@NonRestartableComposable
@Composable
fun rememberAutoInstrumentedNullableAction(
    onAction: (() -> Unit)?,
    id: String,
    elementType: String,
    action: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): (() -> Unit)? = onAction?.let {
    rememberAutoInstrumentedAction(it, id, elementType, action, source, pagePath, pageTitle)
}
