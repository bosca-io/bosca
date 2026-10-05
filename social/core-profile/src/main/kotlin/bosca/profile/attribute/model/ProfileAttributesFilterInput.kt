package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import kotlinx.serialization.Serializable

@Serializable
data class ProfileAttributesFilterInput(
    val attributes: List<String>,
    val childAttributes: ProfileAttributesFilterInput? = null,
    val visibility: ProfileVisibility? = null,
    val confidence: Int? = null,
    val priority: Int? = null,
    val source: String? = null,
    val typeId: String? = null
)
