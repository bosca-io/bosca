package bosca.security.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.profile.service.ProfileService
import bosca.security.encryption.ArgonPassword
import bosca.security.encryption.PasswordEncoderImpl
import bosca.security.encryption.ScryptConfiguration
import bosca.security.encryption.ScryptPassword
import bosca.security.encryption.ScryptPasswordEncoderImpl
import bosca.security.installer.InitialInstaller
import bosca.security.service.PasswordEncoder
import bosca.security.service.SecurityConfiguration
import bosca.security.service.SecurityConfigurationImpl
import bosca.security.service.SecurityService
import bosca.server.BoscaApplication

@Providers
class Configuration {

    @Provider(name = "argon2PasswordEncoder", singleton = true)
    fun argon2PasswordEncoder(): PasswordEncoder<ArgonPassword> {
        return PasswordEncoderImpl()
    }

    @Provider(name = "scryptPasswordEncoder", singleton = true)
    fun scryptPasswordEncoder(application: BoscaApplication): PasswordEncoder<ScryptPassword> {
        val configuration: ScryptConfiguration = application.environment.config.property("security.scrypt").getAs()
        return ScryptPasswordEncoderImpl(configuration)
    }

    @Provider(singleton = true)
    fun securityConfiguration(application: BoscaApplication): SecurityConfiguration {
        return SecurityConfigurationImpl(application)
    }

    @Provider(singleton = true, name = "security-initial")
    fun initialInstaller(
        application: BoscaApplication,
        securityService: SecurityService,
        profileService: ProfileService
    ): PackageInstaller = InitialInstaller(
        securityService,
        profileService,
        application
    )

    @Provider(name = "security-initial")
    fun securityInitialPackage(): PackageInstallation = PackageInstallation(
        key = "security-initial",
        name = "Security Initial Installer",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("security-initial")
            )
        )
    )
}
