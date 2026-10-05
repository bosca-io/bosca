package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay

/** Adds a dwell-based visible-content impression without recording rendered content. */
@NonRestartableComposable
@Composable
fun autoInstrumentedVisibilityModifier(
    modifier: Modifier?,
    id: String,
    elementType: String,
    source: String,
    pagePath: String,
    pageTitle: String,
    threshold: Float,
    dwellMillis: Long,
): Modifier {
    validateVisibilityConfiguration(threshold, dwellMillis)
    val analytics = LocalAnalytics.current
    val context = automaticContext(pagePath, pageTitle)
    var visible by remember(id, source) { mutableStateOf(false) }
    var recorded by remember(id, source) { mutableStateOf(false) }

    LaunchedEffect(visible, recorded, analytics, id, context, threshold, dwellMillis) {
        if (!visible || recorded) return@LaunchedEffect
        delay(dwellMillis)
        if (visible && !recorded) {
            recorded = true
            recordComposeImpression(
                analytics = analytics,
                context = context,
                id = id,
                elementType = elementType,
                source = source,
                extras = mapOf(
                    "visibility_threshold" to threshold.toString(),
                    "dwell_ms" to dwellMillis.toString(),
                ),
            )
        }
    }

    return (modifier ?: Modifier).onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates()
        visible = visibleFraction(
            bounds = coordinates.boundsInRoot(),
            rootWidth = root.size.width.toFloat(),
            rootHeight = root.size.height.toFloat(),
        ) >= threshold
    }
}
