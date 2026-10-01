package bosca.messages.models

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.security.model.Principal
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

open class OrganizationModel(
    val organization: Organization,
    val profile: Profile,
    val attributes: List<ProfileAttribute>
) {
    private val attributesMap = attributes.associateBy { it.typeId }

    val name = organization.name
    val avatar: String? = null
}

class PagingOrganizationModel(
    organization: Organization,
    profile: Profile,
    attributes: List<ProfileAttribute>,
    nextOffset: Long?
) : OrganizationModel(organization, profile, attributes) {

    val hasOffset = nextOffset != null
    val nextOffsetPage = "/organizations?paging=true&offset=$nextOffset"
}