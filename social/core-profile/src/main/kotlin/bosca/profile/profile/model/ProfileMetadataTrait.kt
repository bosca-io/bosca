package bosca.profile.profile.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ProfileMetadataTrait(
    @Contextual
    val profileId: UUID,
    val traitId: String,
    val value: String? = null
)
