package bosca.hubspot.installer

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.service.PipelineService
import bosca.profile.profile.service.ProfileService

/** Registers the HubSpot package installation and its installers with the DI system. */
@Providers
class PackageInstallerRegistry {

    @Provider(name = "hubspot-attribute-types")
    fun hubSpotAttributeTypesInstaller(
        service: ProfileService
    ): PackageInstaller = HubSpotAttributeTypesInstaller(service)

    @Provider(name = "hubspot-pipelines")
    fun hubSpotPipelinesInstaller(
        pipelines: PipelineService
    ): PackageInstaller = HubSpotPipelinesInstaller(pipelines)

    @Provider(name = "hubspot")
    fun hubSpotPackage(): PackageInstallation = PackageInstallation(
        key = "hubspot",
        name = "HubSpot",
        versions = listOf(
            PackageInstallationVersion(version = "1.0.0", installerNames = listOf("hubspot-attribute-types")),
            // The pipelines that replace the legacy HubSpot sync jobs (event-triggered + the sync flow).
            PackageInstallationVersion(version = "1.1.0", installerNames = listOf("hubspot-pipelines")),
        )
    )
}
