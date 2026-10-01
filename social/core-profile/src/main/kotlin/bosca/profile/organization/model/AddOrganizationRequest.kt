package bosca.profile.organization.model

import bosca.profile.profile.model.ProfileInput
import kotlinx.serialization.Serializable

@Serializable
data class AddOrganizationRequest(
    val organization: OrganizationInput,
    val profile: ProfileInput
)
