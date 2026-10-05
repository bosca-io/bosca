package bosca.profile.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService

class AttributeTypesInstaller(
    private val service: ProfileService
) : PackageInstaller {
    override val version: String = "1.0.2"

    private fun newInput(
        id: String,
        name: String,
        description: String,
        visibility: ProfileVisibility,
        isProtected: Boolean
    ): ProfileAttributeTypeInput = ProfileAttributeTypeInput(id, name, description, visibility, isProtected)

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getAttributeTypes().associateBy { it.id }
        listOf(
            newInput(
                id = "bosca.profiles.name",
                name = "Name",
                description = "A Profile Name",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.name.given",
                name = "Given Name",
                description = "A Profile Given Name",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.name.family",
                name = "Family Name",
                description = "A Profile Family Name",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.email",
                name = "Email",
                description = "A Profile Email",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.locale",
                name = "Preferred Locale",
                description = "There profile's preferred locale",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.timezone",
                name = "Preferred Timezone",
                description = "There profile's preferred timezone",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.bio",
                name = "Bio",
                description = "A Profile Bio",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.comment.disabled",
                name = "Commenting Disabled",
                description = "Profile specific Commenting Disabled",
                visibility = ProfileVisibility.SYSTEM,
                isProtected = true
            ),
            newInput(
                id = "bosca.profiles.comment.moderator",
                name = "Moderator",
                description = "Comment Moderation Enabled",
                visibility = ProfileVisibility.USER,
                isProtected = true
            ),
            newInput(
                id = "bosca.profiles.organization.church.tradition",
                name = "Tradition",
                description = "Church Tradition",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.organization.church.audience.size",
                name = "Audience Size",
                description = "Church Audience Size",
                visibility = ProfileVisibility.USER,
                isProtected = false
            ),
            newInput(
                id = "bosca.profiles.country",
                name = "Country",
                description = "Country",
                visibility = ProfileVisibility.USER,
                isProtected = false
            )
        ).forEach {
            if (!current.containsKey(it.id)) {
                service.addAttributeType(it)
            } else {
                service.editAttributeType(it)
            }
        }
    }
}