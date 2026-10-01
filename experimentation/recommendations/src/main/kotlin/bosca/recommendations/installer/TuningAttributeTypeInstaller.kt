package bosca.recommendations.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService

/**
 * Registers the profile attribute type feed clients persist their tuning preferences under.
 *
 * Tuning is the per-profile Boost / Lower / Hide weighting of feed content by subject
 * (lowercased category name), source (display host), and article type. Clients store ONE
 * [TUNING_TYPE] attribute per profile whose `attributes` payload is:
 *
 * ```json
 * {"subjects": {"science": "BOOST"}, "sources": {"example.news": "HIDDEN"}, "kinds": {"opinion": "LOWER"}}
 * ```
 *
 * with weights `BOOST` / `LOWER` / `HIDDEN` (an absent key is Normal). Storing it on the profile —
 * rather than device-locally — makes tuning follow the account across clients and leaves the
 * signal readable to the serve plane. The install is add-or-edit, so it is safe when the type
 * already exists.
 */
class TuningAttributeTypeInstaller(
    private val service: ProfileService,
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val current = service.getAttributeTypes().associateBy { it.id }
        val input = ProfileAttributeTypeInput(
            id = TUNING_TYPE,
            name = "Feed Tuning",
            description = "Per-profile Boost / Lower / Hide weights for feed subjects, sources, and article types",
            visibility = ProfileVisibility.USER,
            // Must stay writable: addAttributes rejects protected-type writes from non-admin
            // callers, which would fail the clients' own tuning writes.
            protected = false,
        )
        if (!current.containsKey(input.id)) {
            service.addAttributeType(input)
        } else {
            service.editAttributeType(input)
        }
    }

    companion object {

        /** The attribute type id feed clients store their tuning preferences under. */
        const val TUNING_TYPE = "bosca.recommendations.tuning"
    }
}
