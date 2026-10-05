package bosca.installer.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.installer.model.InstalledPackage
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstallationService
import kotlinx.serialization.Serializable

@Serializable
data class PackageInstallationVersionContext(
    val installation: PackageInstallation,
    val version: PackageInstallationVersion
)

@TypeController(type = "PackageInstallationVersion")
class PackageInstallationVersionController(
    private val service: PackageInstallationService
) : GraphQLController<PackageInstallationVersionContext> {

    @Field
    fun version(source: PackageInstallationVersionContext): String = source.version.version

    @Field
    suspend fun installers(source: PackageInstallationVersionContext): List<InstalledPackage> {
        return source.version.installerNames.map { name ->
            val installer = source.version.getInstaller(name)
            InstalledPackage(name, installer.version)
        }
    }

    @Field
    suspend fun installed(source: PackageInstallationVersionContext): List<InstalledPackage> {
        val history = service.getHistory().associateBy { it.historyKey }
        return source.version
            .installerNames
            .flatMap { name ->
                val installerName = source.version.installationKey(source.installation, name)
                history.filter { it.key.startsWith("$installerName:") }
                    .map {
                        InstalledPackage(name, it.value.version)
                    }
            }
    }
}

@TypeController
class InstalledPackageController : GraphQLController<InstalledPackage> {

    @Field
    fun name(source: InstalledPackage): String = source.name

    @Field
    fun version(source: InstalledPackage): String = source.version
}