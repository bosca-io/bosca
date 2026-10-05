package bosca.analytics.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import kotlin.math.roundToInt

/** Stable Compose ABI used to observe a regular horizontal or vertical scroll state. */
@NonRestartableComposable
@Composable
fun autoInstrumentedScrollState(
    state: ScrollState,
    id: String,
    axis: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): ScrollState {
    val context = automaticContext(pagePath, pageTitle)
    TrackScrollDepth(id, axis, source, context) {
        if (state.maxValue <= 0) 0 else ((state.value.toDouble() / state.maxValue) * 100).roundToInt()
    }
    return state
}
