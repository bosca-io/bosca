package bosca.content.metadata.service

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class GuideCacheKeyId(
    val id: UUID,
    val version: Int? = null,
    val stepId: Long? = null
)
