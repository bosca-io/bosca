package bosca.analytics.api

import kotlinx.serialization.Serializable

/** Optional coarse location supplied explicitly by the host application. */
@Serializable
data class Geo(
    val city: String = "",
    val region: String = "",
    val country: String = "",
)
