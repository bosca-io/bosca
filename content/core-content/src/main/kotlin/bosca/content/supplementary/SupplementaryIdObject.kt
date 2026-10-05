package bosca.content.supplementary

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class SupplementaryIdObject(
    @Contextual
    val contentId: UUID,
    @Contextual
    val id: UUID,
    val key: String,
    @Contextual
    val planId: UUID?,
    @Contextual
    val jobId: UUID?
)