package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember

/** Stable Compose ABI used to track checkbox, switch, and selection state changes. */
@NonRestartableComposable
@Composable
fun rememberAutoInstrumentedBooleanChange(
    onChange: (Boolean) -> Unit,
    id: String,
    elementType: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): (Boolean) -> Unit {
    val analytics = LocalAnalytics.current
    val context = automaticContext(pagePath, pageTitle)
    return remember(analytics, onChange, id, elementType, source, context) {
        { selected ->
            recordComposeInteraction(
                analytics = analytics,
                context = context,
                id = id,
                elementType = elementType,
                source = source,
                extras = mapOf("selected" to selected.toString()),
            )
            onChange(selected)
        }
    }
}

/** Nullable counterpart used by controls whose disabled state is represented by a null callback. */
@NonRestartableComposable
@Composable
fun rememberAutoInstrumentedNullableBooleanChange(
    onChange: ((Boolean) -> Unit)?,
    id: String,
    elementType: String,
    source: String,
    pagePath: String,
    pageTitle: String,
): ((Boolean) -> Unit)? = onChange?.let {
    rememberAutoInstrumentedBooleanChange(it, id, elementType, source, pagePath, pageTitle)
}
