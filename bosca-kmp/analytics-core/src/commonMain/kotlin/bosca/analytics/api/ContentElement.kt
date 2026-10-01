package bosca.analytics.api

import kotlinx.serialization.Serializable

/** Content associated with an analytics element. */
@Serializable
data class ContentElement(
    val id: String,
    val type: String,
    val index: Int? = null,
    val percent: Double? = null,
)
