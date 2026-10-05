package bosca.installer.service

import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.provide
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.model.PackageInstallations
import bosca.installer.repository.PackageInstallationsRepository
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

@Provider(singleton = true, name = "core")
class CorePackageInstallerProvider : ObjectProvider<PackageInstallation> {
    override val type: KClass<PackageInstallation> = PackageInstallation::class

    override suspend fun `get`(): PackageInstallation = PackageInstallation(
        key = "core",
        name = "Core Package Installer",
        versions = listOf(
            PackageInstallationVersion(
                version = "3.12.0",
                installerNames = listOf("upgrader")
            )
        )
    )
}

@Provider(singleton = true, name = "upgrader")
class UpgradePackageInstallerProvider : ObjectProvider<PackageInstaller> {
    override val type: KClass<PackageInstaller> = PackageInstaller::class

    override suspend fun `get`(): PackageInstaller = UpgradeInstaller(provide())
}

class UpgradeInstaller(
    private val repository: PackageInstallationsRepository
) : PackageInstaller {
    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val history = repository.getHistory().associateBy { it.historyKey }
        val installations = PackageInstallations.get()
        installations.installations.forEach { pkg ->
            pkg.versions.forEach { pkgVersion ->
                val historyVersion = pkgVersion.historyKey(pkg)
                if (history.containsKey(historyVersion)) {
                    pkgVersion.installerNames.forEach { name ->
                        val installer = pkgVersion.getInstaller(name)
                        val installerHistoryKey = pkgVersion.historyKey(pkg, name, installer)
                        if (!history.containsKey(installerHistoryKey)) {
                            log.info("marking installer as installed: $installerHistoryKey")
                            repository.addHistory(
                                PackageInstallationHistory(
                                    key = pkgVersion.installationKey(pkg, name),
                                    version = installer.version,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(UpgradeInstaller::class.java)
    }
}
