package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlin.time.Clock

/** Stable Compose ABI used to track input focus and dwell without recording field values. */
@NonRestartableComposable
@Composable
fun autoInstrumentedInputModifier(
    modifier: Modifier?,
    id: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): Modifier {
    val analytics = LocalAnalytics.current
    val context = automaticContext(pagePath, pageTitle)
    val tracker = remember(id, source) { InputFocusTracker() }
    var focused by remember(id, source) { mutableStateOf(false) }

    DisposableEffect(analytics, id, source, context, tracker) {
        onDispose {
            tracker.blur(Clock.System.now().toEpochMilliseconds())?.let { dwell ->
                recordComposeInteraction(
                    analytics = analytics,
                    context = context.copy(elementType = null),
                    id = id,
                    elementType = "field_abandon",
                    source = source,
                    extras = mapOf("dwell_ms" to dwell.toString()),
                )
            }
        }
    }

    LaunchedEffect(focused, analytics, id, source, context, tracker) {
        if (!focused) {
            tracker.blur(Clock.System.now().toEpochMilliseconds())?.let { dwell ->
                recordComposeInteraction(
                    analytics = analytics,
                    context = context.copy(elementType = null),
                    id = id,
                    elementType = "field_blur",
                    source = source,
                    extras = mapOf("dwell_ms" to dwell.toString()),
                )
            }
        }
    }

    return (modifier ?: Modifier).onFocusChanged { focus ->
        val now = Clock.System.now().toEpochMilliseconds()
        if (focus.isFocused) {
            focused = true
            if (tracker.focus(now)) {
                recordComposeInteraction(
                    analytics = analytics,
                    context = context.copy(elementType = null),
                    id = id,
                    elementType = "field_focus",
                    source = source,
                )
            }
        } else {
            focused = false
        }
    }
}
