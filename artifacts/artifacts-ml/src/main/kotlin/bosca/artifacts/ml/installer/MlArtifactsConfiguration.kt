package bosca.artifacts.ml.installer

import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.security.service.ApiTokenService
import bosca.security.service.SecurityService

/**
 * Registers [MlArtifactsInstaller] so the artifacts server's `PackageInstallations.install()` provisions the
 * ML model namespace + service tokens on first boot. The provider name matches the version's installerNames.
 */
@Providers
class MlArtifactsConfiguration {

    @Provider(singleton = true, name = "artifacts-ml")
    fun mlArtifactsInstaller(
        securityService: SecurityService,
        apiTokenService: ApiTokenService,
        artifactService: ArtifactRepositoryService,
    ): PackageInstaller = MlArtifactsInstaller(securityService, apiTokenService, artifactService)

    @Provider(name = "artifacts-ml")
    fun mlArtifactsPackage(): PackageInstallation = PackageInstallation(
        key = "artifacts-ml",
        name = "ML Model Artifacts Provisioning",
        versions = listOf(
            PackageInstallationVersion(version = "1.0.0", installerNames = listOf("artifacts-ml")),
        ),
    )
}
