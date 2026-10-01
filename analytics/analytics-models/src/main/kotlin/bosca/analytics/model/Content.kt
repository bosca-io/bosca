package bosca.analytics.model

import kotlinx.serialization.Serializable

@Serializable
data class Content(
    val id: String,
    val type: String,
    val index: Long? = null,
    val percent: Double? = null
)