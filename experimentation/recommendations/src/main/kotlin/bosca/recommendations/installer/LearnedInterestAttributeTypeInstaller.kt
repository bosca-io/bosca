package bosca.recommendations.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.recommendations.pipeline.InferInterestNode

/**
 * Registers the profile attribute type that learned-interest inference (Phase 4) writes under.
 * The [InferInterestNode] records a rater's inferred interest in content categories here as a **learned**
 * attribute (`confidence < 100`, `source = "learned"`), which the Personalization Signals compute then picks
 * up subject to each signal's confidence gate. Must stay writable (not protected) — `addAttributes` silently
 * skips protected types, which would drop the inference. Add-or-edit, so it is safe when the type exists.
 */
class LearnedInterestAttributeTypeInstaller(
    private val service: ProfileService,
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val input = ProfileAttributeTypeInput(
            id = InferInterestNode.LEARNED_INTEREST_TYPE,
            name = "Learned Interest",
            description = "A content category a profile has shown behavioral interest in (inferred from high ratings; confidence < 100)",
            visibility = ProfileVisibility.USER,
            protected = false,
        )
        if (service.getAttributeTypes().none { it.id == input.id }) {
            service.addAttributeType(input)
        } else {
            service.editAttributeType(input)
        }
    }
}
