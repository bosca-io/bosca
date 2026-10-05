package bosca.profile.organization.model


import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OrganizationInput(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    val profile: UUID = UUID.NIL,
    val name: String,
    @Contextual
    val attributes: JsonElement,
    @Contextual
    val systemAttributes: JsonElement,
    val slug: String? = null,
    val visibility: ProfileVisibility,
    val profileAttributes: List<ProfileAttributeInput> = emptyList(),
    val domains: List<OrganizationDomainInput> = emptyList(),
    val signupEmails: List<OrganizationSignupEmailInput> = emptyList(),
    val signupTokens: List<OrganizationSignupTokenInput> = emptyList()
) {

    fun toOrganization() = Organization(id, name, attributes, systemAttributes, visibility, profile)
    fun toOrganization(profile: UUID) = Organization(id, name, attributes, systemAttributes, visibility, profile)
}