package bosca.installer.service

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.model.PackageInstallations
import bosca.installer.repository.PackageInstallationsRepository
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

@ServiceImplementation
class PackageInstallationServiceImpl(
    private val repository: PackageInstallationsRepository
) : PackageInstallationService {

    override suspend fun install(keys: Set<String>) {
        val installations = PackageInstallations.get()
        val history = repository.getHistory().associateBy { it.historyKey }
        installations.installations.filter { keys.contains(it.key) }.forEach { installation ->
            installation.versions.forEach {
                install(history, installation, it)
            }
        }
    }

    override suspend fun install(key: String, version: String): Boolean {
        val installations = PackageInstallations.get()
        val installation = installations.installations.find { it.key == key } ?: return false
        val history = repository.getHistory().associateBy { it.historyKey }
        val versionDef = installation.versions.find { it.version == version } ?: return false
        install(history, installation, versionDef)
        return true
    }

    private suspend fun install(history: Map<String, PackageInstallationHistory>, installation: PackageInstallation, version: PackageInstallationVersion) {
        if (history.containsKey(version.historyKey(installation))) return
        log.info("installing: ${installation.name} - $version")
        version.installerNames.zip(version.getInstallers()).forEach { (name, installer) ->
            install(history, installation, version, name, installer)
        }
        repository.addHistory(PackageInstallationHistory(key = installation.key, version = version.version))
    }

    private suspend fun install(history: Map<String, PackageInstallationHistory>, installation: PackageInstallation, version: PackageInstallationVersion, name: String, installer: PackageInstaller): Boolean {
        val historyKey = version.historyKey(installation, name, installer)
        if (history.containsKey(historyKey)) return false
        log.info("executing installer: $historyKey")
        installer.install(installation, version)
        repository.addHistory(PackageInstallationHistory(key = version.installationKey(installation, name), version = installer.version))
        return true
    }

    override suspend fun install(key: String, version: String, installerName: String, installerVersion: String): Boolean {
        val installations = PackageInstallations.get()
        val installation = installations.installations.find { it.key == key } ?: return false
        val version = installation.versions.find { it.version == version } ?: return false
        val history = repository.getHistory().associateBy { it.historyKey }
        return install(history, installation, version, installerName, version.getInstaller(installerName))
    }

    override suspend fun getHistory(): List<PackageInstallationHistory> {
        return repository.getHistory()
    }

    companion object {

        private val log = LoggerFactory.getLogger(PackageInstallationServiceImpl::class.java)
    }
}
