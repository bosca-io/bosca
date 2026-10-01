package bosca.profile.rating.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ProfileRatingInput(
    val rating: Int,
    @Contextual
    val metadataId: UUID? = null,
    val metadataVersion: Int? = null,
    @Contextual
    val collectionId: UUID? = null
)
