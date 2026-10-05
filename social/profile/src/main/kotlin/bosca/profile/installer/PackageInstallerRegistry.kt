package bosca.profile.installer

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.forms.service.FormSchemaService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.profile.profile.service.ProfileService

@Providers
class PackageInstallerRegistry {

    @Provider(name = "attribute-types")
    fun attributeTypesInstaller(
        service: ProfileService
    ): PackageInstaller = AttributeTypesInstaller(service)

    @Provider(name = "attribute-type-form-schemas")
    fun attributeTypeFormSchemaInstaller(
        profileService: ProfileService,
        formSchemaService: FormSchemaService
    ): PackageInstaller = ProfileAttributeFormSchemaInstaller(profileService, formSchemaService)

    @Provider(name = "profiles")
    fun profilesPackage(): PackageInstallation = PackageInstallation(
        key = "profiles",
        name = "Profiles",
        versions = listOf(
            PackageInstallationVersion(version = "1.0.0", installerNames = listOf("attribute-types")),
            PackageInstallationVersion(version = "1.0.1", installerNames = listOf("attribute-types")),
            PackageInstallationVersion(version = "1.0.2", installerNames = listOf("attribute-types")),
            PackageInstallationVersion(version = "1.0.3", installerNames = listOf("attribute-types", "attribute-type-form-schemas")),
            PackageInstallationVersion(version = "1.0.4", installerNames = listOf("attribute-type-form-schemas")),
        )
    )
}
