package bosca.analytics.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import kotlin.math.roundToInt

/** Stable Compose ABI used to provide and observe a bounded lazy-list state. */
@NonRestartableComposable
@Composable
fun autoInstrumentedLazyListState(
    state: LazyListState?,
    id: String,
    axis: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): LazyListState {
    val resolved = state ?: rememberLazyListState()
    val context = automaticContext(pagePath, pageTitle)
    TrackScrollDepth(id, axis, source, context) {
        val layout = resolved.layoutInfo
        val total = layout.totalItemsCount
        val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (total <= 0 || last < 0) 0 else (((last + 1).toDouble() / total) * 100).roundToInt()
    }
    return resolved
}
