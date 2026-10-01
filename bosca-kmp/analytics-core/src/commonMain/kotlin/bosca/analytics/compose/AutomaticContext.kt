package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import bosca.analytics.api.Page
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext

@NonRestartableComposable
@Composable
internal fun automaticContext(pagePath: String, pageTitle: String): AnalyticsInstrumentationContext {
    val inherited = LocalAnalyticsContext.current
    val page = pagePath.takeIf { it.isNotBlank() }?.let { Page(path = it, title = pageTitle.ifBlank { null }) }
    return inherited.merge(AnalyticsInstrumentationContext(page = page))
}
