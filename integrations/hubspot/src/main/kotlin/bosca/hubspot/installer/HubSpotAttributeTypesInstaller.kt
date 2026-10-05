package bosca.hubspot.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService

/**
 * Registers the profile attribute types the HubSpot integration writes.
 *
 * The sync jobs store the HubSpot object id on a profile as a `bosca.profiles.hubspot.id`
 * attribute; without this type registered, those writes fail with a missing-type error.
 * The install is add-or-edit, so it is safe when the type already exists (e.g. created by hand).
 */
class HubSpotAttributeTypesInstaller(
    private val service: ProfileService
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getAttributeTypes().associateBy { it.id }
        listOf(
            ProfileAttributeTypeInput(
                id = HUBSPOT_ID_TYPE,
                name = "HubSpot ID",
                description = "The HubSpot object id this profile is synced to",
                visibility = ProfileVisibility.SYSTEM,
                // Must stay writable: addAttributes rejects protected-type writes unless the caller
                // opts in, which would fail the sync jobs' writes. SYSTEM visibility keeps it out of
                // user-facing surfaces.
                protected = false
            )
        ).forEach {
            if (!current.containsKey(it.id)) {
                service.addAttributeType(it)
            } else {
                service.editAttributeType(it)
            }
        }
    }

    companion object {

        /** The attribute type id the sync jobs use to store a profile's HubSpot object id. */
        const val HUBSPOT_ID_TYPE = "bosca.profiles.hubspot.id"
    }
}
