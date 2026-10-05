package bosca.analytics.api

import kotlinx.serialization.Serializable

/** Optional route or screen snapshot captured when an event is created. */
@Serializable
data class Page(
    val path: String? = null,
    val url: String? = null,
    val title: String? = null,
)
