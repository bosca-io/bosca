package bosca.languages.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.languages.installer.LanguagePackageInstaller
import bosca.languages.service.LanguagesService

@Providers
class Configuration {

    @Provider(name = "language-installer")
    fun languageInstaller(languages: LanguagesService): PackageInstaller = LanguagePackageInstaller(languages)

    @Provider(name = "languages")
    fun languagesPackage(): PackageInstallation = PackageInstallation(
        key = "languages",
        name = "Languages",
        versions = listOf(
            PackageInstallationVersion(
                version = "1.0.0",
                installerNames = listOf("language-installer")
            ),
            PackageInstallationVersion(
                version = "1.1.0",
                installerNames = listOf("language-installer")
            ),
            PackageInstallationVersion(
                version = "1.2.0",
                installerNames = listOf("language-installer")
            )
        )
    )
}
