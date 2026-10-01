package bosca.profile.profile.model

import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.model.ProfileVisibility
import kotlinx.serialization.Serializable

@Serializable
data class ProfileInput(
    val slug: String? = null,
    val name: String,
    val attributes: List<ProfileAttributeInput> = emptyList(),
    val visibility: ProfileVisibility,
    val searchable: Boolean? = null,
)
