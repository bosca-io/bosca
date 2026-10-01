package bosca.analytics.model

import kotlinx.serialization.Serializable

@Serializable
data class Browser(
    val agent: String
)