package bosca.installer.model

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.di.annotation.InternalDI
import bosca.di.ProviderRegistry
import bosca.di.provide
import bosca.installer.service.PackageInstallationService
import bosca.installer.service.PackageInstaller
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PackageInstallations(val installations: List<PackageInstallation>) {

    companion object {
        @OptIn(InternalDI::class)
        suspend fun get() = PackageInstallations(
            ProviderRegistry.findAll(PackageInstallation::class).map { it.get() }
        )

        suspend fun install(application: BoscaApplication) {
            withRequestCache {
                withConnectionManager {
                    val keys = try {
                        application.environment.config.property("packages").getAs<Set<String>>()
                    } catch (_: IllegalStateException) {
                        emptySet()
                    }
                    val installerService = provide<PackageInstallationService>()
                    installerService.install(keys)
                }
            }
        }
    }
}

@Serializable
data class PackageInstallationHistory(
    @Contextual
    val id: UUID = UUID.NIL,
    val key: String,
    val version: String,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now()
) {

    val historyKey: String = "$key:$version"
}

@Serializable
data class PackageInstallation(
    val key: String,
    val name: String,
    val versions: List<PackageInstallationVersion>
)

@Serializable
data class PackageInstallationVersion(
    val version: String,
    @SerialName("installers")
    val installerNames: List<String>
) {

    fun historyKey(installation: PackageInstallation) = "${installation.key}:$version"

    fun historyKey(installation: PackageInstallation, installerName: String, installer: PackageInstaller) = "${installation.key}:$installerName:${installer.version}"

    fun installationKey(installation: PackageInstallation, installerName: String) = "${installation.key}:$installerName"

    suspend fun getInstaller(name: String): PackageInstaller {
        if (!installerNames.contains(name)) error("installer isn't registered for this package installation version: $name")
        return provide(name)
    }

    suspend fun getInstallers(): List<PackageInstaller> = installerNames.map { provide(it) }
}

@Serializable
data class InstalledPackage(val name: String, val version: String)
