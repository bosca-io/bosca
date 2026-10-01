package bosca.messages.models

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.security.model.Principal
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

open class ProfileModel(
    val profile: Profile,
    val principal: Principal?,
    val attributes: List<ProfileAttribute>,
    val organizations: List<Organization>
) {
    private val attributesMap = attributes.associateBy { it.typeId }

    val name = attributesMap["bosca.profiles.name"]?.attributes?.jsonObject?.get("name")?.jsonPrimitive?.content ?: profile.name

    val email = attributesMap["bosca.profiles.email"]?.attributes?.jsonObject?.get("email")?.jsonPrimitive?.content ?: ""

    /** The one-time token on the email attribute, used to render the email-verification link (the framework's source of truth — not the principal token, which is the password-reset token). */
    val emailVerificationToken = attributesMap["bosca.profiles.email"]?.verificationToken

    /** The raw web origin the verification was requested from (multi-host routing); validated when the link is built. */
    val emailVerificationOrigin = attributesMap["bosca.profiles.email"]?.verificationOrigin

    val avatar = attributesMap["bosca.profiles.avatar"]?.attributes?.jsonObject?.get("picture")?.jsonPrimitive?.content

    val verified: Boolean = principal?.verified ?: false
}

class PagingProfileModel(
    profile: Profile,
    principal: Principal?,
    attributes: List<ProfileAttribute>,
    organizations: List<Organization>,
    nextOffset: Long?
) : ProfileModel(profile, principal, attributes, organizations) {

    val hasOffset = nextOffset != null
    val nextOffsetPage = "/profiles?paging=true&offset=$nextOffset"
}