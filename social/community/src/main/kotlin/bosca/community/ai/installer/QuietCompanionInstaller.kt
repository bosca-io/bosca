package bosca.community.ai.installer

import bosca.community.configuration.Constants
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.io.encoding.Base64

class QuietCompanionInstaller(
    private val securityService: SecurityService,
    private val profileService: ProfileService,
    private val application: BoscaApplication
) : PackageInstaller {
    override val version: String = "1.1.0"

    private val secureRandom by lazy { SecureRandom() }

    private fun generatePassword(): String {
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        return Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(tokenBytes)
    }

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val identifier = Constants.COMPANION_NAME
        val messagingGroup = securityService.getGroupByName(MESSAGING_GROUP, GroupType.SYSTEM)
            ?: error("Messaging group not found")
        val existingPrincipal = securityService.getPrincipalByIdentifier(identifier)
        val principal = when {
            existingPrincipal == null -> {
                val initialPassword = application.environment.config
                    .propertyOrNull("initialization.password.$identifier")
                    ?.getString()
                    ?: generatePassword()
                securityService.addPrincipal(
                    Principal(
                        verified = true,
                        anonymous = false,
                    ),
                    SimplePasswordAttributes(identifier, initialPassword),
                ).asPrincipal()
            }
            existingPrincipal.deletedAt != null -> securityService.restorePrincipal(existingPrincipal.id)
            else -> existingPrincipal
        }
        val principalId = principal.id

        if (securityService.getPrincipalGroups(principalId).none { it.id == messagingGroup.id }) {
            securityService.addPrincipalGroup(principalId, messagingGroup.id)
        }
        val profiles = profileService.getByPrincipal(principalId)
        if (profiles.none { !it.isDeleted }) {
            val deletedProfile = profiles.firstOrNull()
            if (deletedProfile == null) {
                profileService.add(
                    ProfileInput(
                        name = identifier,
                        visibility = ProfileVisibility.USER,
                    ),
                    ProfileType.GENERIC,
                    principalId,
                )
            } else {
                profileService.restore(deletedProfile.id)
            }
        }
        log.info("Quiet Companion agent is ready: $identifier")
    }

    companion object {
        private const val MESSAGING_GROUP = "messaging"
        private val log = LoggerFactory.getLogger(QuietCompanionInstaller::class.java)
    }
}
