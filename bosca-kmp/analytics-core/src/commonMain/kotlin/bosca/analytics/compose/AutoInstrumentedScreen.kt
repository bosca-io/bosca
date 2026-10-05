package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.Page
import bosca.analytics.instrumentation.AnalyticsInstrumentationContext

/** Stable Compose ABI injected into detected or annotated screen functions. */
@NonRestartableComposable
@Composable
fun AutoInstrumentedScreen(
    id: String,
    path: String,
    title: String,
    source: String,
) {
    val analytics = LocalAnalytics.current
    val inherited = LocalAnalyticsContext.current
    val owner = remember { Any() }
    val page = Page(
        path = path.ifBlank { inherited.page?.path ?: id },
        url = inherited.page?.url,
        title = title.ifBlank { inherited.page?.title },
    )
    val context = inherited.merge(AnalyticsInstrumentationContext(page = page))
    DisposableEffect(analytics, owner) {
        onDispose { analytics.leavePage(owner) }
    }
    SideEffect {
        analytics.recordAutomaticPage(
            owner = owner,
            page = page,
            element = AnalyticsElement(
                id = context.elementId ?: id,
                type = context.elementType ?: "page",
                content = context.content,
                extras = context.extras + mapOf(
                    "instrumentation" to "compose",
                    "source" to source,
                ),
            ),
        )
    }
}
