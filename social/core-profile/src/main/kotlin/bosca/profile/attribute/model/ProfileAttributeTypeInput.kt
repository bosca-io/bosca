package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ProfileAttributeTypeInput(
    val id: String,
    val name: String,
    val description: String,
    val visibility: ProfileVisibility,
    val protected: Boolean,
    @Contextual
    val formSchemaId: UUID? = null
)