package bosca.security.installer

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

class InitialInstaller(
    private val securityService: SecurityService,
    private val profileService: ProfileService,
    private val application: BoscaApplication
) : PackageInstaller {
    override val version: String = "1.0.0"

    private val secureRandom by lazy { SecureRandom() }

    private fun generatePassword(): String {
        val tokenBytes = ByteArray(32) // 256 bits
        secureRandom.nextBytes(tokenBytes)
        return Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(tokenBytes)
    }

    private suspend fun generatePrincipal(name: String) {
        securityService.getPrincipalByIdentifier(name)?.let { return }
        val configuredPassword = application.environment.config.propertyOrNull("initialization.password.$name")
        val initialPassword = if (configuredPassword == null) generatePassword() else configuredPassword.getString()
        val principal = securityService.addPrincipal(
            Principal(
                verified = true,
                anonymous = false
            ), SimplePasswordAttributes(name, initialPassword)
        )
        if (name == "admin") {
            securityService.addPrincipalGroup(
                principal.id,
                requireSystemGroup("administrators", "Administrators group not found"),
            )
        }
        if (name == "sa") {
            securityService.addPrincipalGroup(
                principal.id,
                requireSystemGroup("sa", "SA group not found"),
            )
        }
        profileService.add(
            ProfileInput(
                name = name,
                visibility = ProfileVisibility.USER,
            ),
            ProfileType.GENERIC,
            principal.id
        )
        log.warn("Created initial user: $name -> password: $initialPassword")
    }

    private suspend fun requireSystemGroup(name: String, message: String) =
        securityService.getGroupByName(name, GroupType.SYSTEM)?.id ?: error(message)

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        generatePrincipal("admin")
        generatePrincipal("sa")
    }

    companion object {

        private val log = LoggerFactory.getLogger(InitialInstaller::class.java)
    }
}
