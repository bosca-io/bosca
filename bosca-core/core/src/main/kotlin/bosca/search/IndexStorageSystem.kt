package bosca.search

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class IndexStorageSystem(
    val id: UUID? = null,
    val name: String? = null
)