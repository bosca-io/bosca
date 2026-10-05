package bosca.content.find

import kotlinx.serialization.Serializable

@Serializable
data class FindAttributeInput(
    val key: String,
    val value: String? = null
)