package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.compositionLocalOf
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext

/** Analytics metadata inherited by automatically instrumented Compose descendants. */
val LocalAnalyticsContext = compositionLocalOf { AnalyticsInstrumentationContext.Empty }

/** Adds [context] to the analytics metadata inherited by [content]. */
@NonRestartableComposable
@Composable
fun ProvideAnalyticsContext(
    context: AnalyticsInstrumentationContext,
    content: @Composable () -> Unit,
) {
    val merged = LocalAnalyticsContext.current.merge(context)
    CompositionLocalProvider(LocalAnalyticsContext provides merged, content = content)
}
