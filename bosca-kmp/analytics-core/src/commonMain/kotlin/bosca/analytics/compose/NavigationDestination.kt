package bosca.analytics.compose

import bosca.analytics.api.AnalyticsNavigationRoute
import bosca.analytics.api.Page

internal data class NavigationDestination(
    val page: Page,
    val routeType: String,
    val extras: Map<String, String>,
)

internal fun navigationDestination(key: Any): NavigationDestination {
    val route = key as? AnalyticsNavigationRoute
    val routeType = key::class.simpleName.orEmpty().ifBlank { "route" }
    val path = route?.analyticsPath?.takeIf { it.isNotBlank() }
        ?: (key as? String)?.takeIf { it.isNotBlank() }
        ?: routeType.removeNavigationSuffix().toKebabCase()
    return NavigationDestination(
        page = Page(
            path = path.asPagePath(),
            title = route?.analyticsTitle,
        ),
        routeType = routeType,
        extras = route?.analyticsExtras.orEmpty(),
    )
}

private fun String.asPagePath(): String = if (startsWith('/')) this else "/$this"

private fun String.removeNavigationSuffix(): String =
    removeSuffix("Screen").removeSuffix("Page").removeSuffix("Route")

private fun String.toKebabCase(): String = buildString {
    this@toKebabCase.forEachIndexed { index, character ->
        if (character.isUpperCase() && index > 0) append('-')
        append(character.lowercaseChar())
    }
}
