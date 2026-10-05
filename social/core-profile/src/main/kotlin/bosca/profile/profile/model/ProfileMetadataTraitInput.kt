package bosca.profile.profile.model

import kotlinx.serialization.Serializable

@Serializable
data class ProfileMetadataTraitInput(
    val traitId: String,
    val value: String? = null
)
