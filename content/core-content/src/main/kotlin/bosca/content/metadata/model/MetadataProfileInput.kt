package bosca.content.metadata.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class MetadataProfileInput(
    @Contextual
    val profileId: UUID,
    val relationship: String
)