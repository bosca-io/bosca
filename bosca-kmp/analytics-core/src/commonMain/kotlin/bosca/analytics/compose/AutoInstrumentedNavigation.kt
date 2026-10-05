package bosca.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType

/** Stable Compose ABI injected into Navigation 3 NavDisplay calls. */
@NonRestartableComposable
@Composable
fun AutoInstrumentedNavigation(
    backStack: List<*>,
    source: String,
) {
    val key = backStack.lastOrNull() ?: return
    val analytics = LocalAnalytics.current
    val inherited = LocalAnalyticsContext.current
    val destination = remember(key) { navigationDestination(key) }
    val paths = backStack.map { route -> route?.let(::navigationDestination)?.page?.path.orEmpty() }
    val page = destination.page.copy(
        url = inherited.page?.url,
        title = destination.page.title ?: inherited.page?.title,
    )
    val owner = remember { Any() }
    val transitions = remember { NavigationTransitionTracker() }
    val commonExtras = inherited.extras + destination.extras + mapOf(
        "instrumentation" to "navigation3",
        "route_type" to destination.routeType,
        "source" to source,
    )

    DisposableEffect(analytics, owner) {
        onDispose { analytics.leavePage(owner) }
    }
    SideEffect {
        analytics.recordAutomaticPage(
            owner = owner,
            page = page,
            element = AnalyticsElement(
                id = inherited.elementId ?: page.path.orEmpty(),
                type = inherited.elementType ?: "page",
                content = inherited.content,
                extras = commonExtras,
            ),
        )
    }

    LaunchedEffect(analytics, page.path, paths, commonExtras) {
        val path = page.path ?: return@LaunchedEffect
        val transition = transitions.move(paths) ?: return@LaunchedEffect
        analytics.logEvent(
            AnalyticsEventInput(
                type = AnalyticsEventType.INTERACTION,
                element = AnalyticsElement(
                    id = path,
                    type = "navigation",
                    extras = commonExtras + mapOf(
                        "action" to transition.action,
                        "depth" to transition.depth.toString(),
                        "from" to transition.from.orEmpty(),
                        "to" to transition.to,
                    ),
                ),
                page = page,
            ),
        )
    }
}
