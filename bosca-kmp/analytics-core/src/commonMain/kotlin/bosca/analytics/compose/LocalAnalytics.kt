package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.staticCompositionLocalOf
import bosca.analytics.api.AnalyticsService

/** Application-provided analytics service inherited by compiler-generated Compose instrumentation. */
val LocalAnalytics = staticCompositionLocalOf<AnalyticsService> {
    error("AnalyticsService must be installed with ProvideAnalytics at the application root")
}

/** Overrides the DI-resolved analytics service for this composition subtree. */
@NonRestartableComposable
@Composable
fun ProvideAnalytics(
    analytics: AnalyticsService,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAnalytics provides analytics, content = content)
}
