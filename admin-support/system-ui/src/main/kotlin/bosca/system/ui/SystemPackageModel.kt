package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class SystemPackageModel(val admin: Boolean, var data: SystemPackagesData? = null, val feedback: SystemFeedback = SystemFeedback(), val tab: String = "packages", var failureTarget: String = "") {
    val tabs get() = listOf(NavigationItem("Packages", "/system/packages", tab == "packages"), NavigationItem("History", "/system/packages?tab=history", tab == "history"))
    val totalVersions get() = data?.packages?.all.orEmpty().sumOf { it.versions.size }
    val installedVersions get() = data?.packages?.all.orEmpty().sumOf { installedCount(it) }
    val summary get() = "${data?.packages?.all.orEmpty().size} packages · $installedVersions/$totalVersions versions installed"
    fun versionInstalled(version: SystemPackagesData.Packages.All.Versions) = version.installers.isNotEmpty() && version.installers.all { installer -> version.installed.any { it.name == installer.name && it.version == installer.version } }
    fun installedCount(pkg: SystemPackagesData.Packages.All) = pkg.versions.count(::versionInstalled)
    fun installationKey(key: String, version: String, name: String, installerVersion: String) = "$key|$version|$name|$installerVersion"
    suspend fun reload(ctx: RenderContext) { if (admin) data = ctx.gql.execute(SystemPackages, Unit) }
    suspend fun refresh(ctx: RenderContext) = feedback.perform { failureTarget = ""; reload(ctx) }
    suspend fun install(key: String, version: String, name: String, installerVersion: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        failureTarget = installationKey(key, version, name, installerVersion)
        require(admin && confirmed) { "Confirm the package installation." }
        val installed = if (name.isBlank()) ctx.gql.execute(SystemInstallPackage, SystemInstallPackage.Variables(key, version)).packages.install
        else ctx.gql.execute(SystemInstallInstaller, SystemInstallInstaller.Variables(key, version, name, installerVersion)).packages.installInstaller
        reload(ctx)
        val current = data?.packages?.all?.firstOrNull { it.key == key }?.versions?.firstOrNull { it.version == version }
        check(installed || current?.installed?.any { it.name == name && it.version == installerVersion } == true) { "Installation did not complete." }
        failureTarget = ""
        feedback.message = if (installed) "Installation completed." else "Installer already installed."
    }
}

suspend fun systemPackages(): SystemPackageModel {
    val ctx = currentRenderContext()
    return SystemPackageModel(systemAdministrator(ctx), tab = if (ctx.query["tab"] == "history") "history" else "packages").also { model -> model.feedback.perform { model.reload(ctx) } }
}
