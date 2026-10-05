package bosca.community.model

import kotlinx.serialization.Serializable

@Serializable
data class Prayers(
    val prayers: List<Prayer>,
    val total: Long
)
