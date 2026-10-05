package bosca.content.metadata.service

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class DocumentCacheKeyId(
    val id: UUID,
    val version: Int? = null,
    val key: String? = null
)
