package bosca.git.installer

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.service.PipelineService
import bosca.pipelines.node.InputNode
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Seeds ordinary synchronization graphs without replacing operator-edited pipelines. */
class GitHubPipelinesInstaller(private val pipelines: PipelineService) : PackageInstaller {
    override val version = "1.1.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        seed("github-import-refs", "GitHub: Import Refs", "bosca.git.model.GitHubDelivery", "githubPush", "delivery")
        seed("github-export-refs", "GitHub: Export Refs", "bosca.git.model.RefUpdateEvent", "githubRef", "event")
        seed("github-reconcile-refs", "GitHub: Reconcile Refs", InputNode.JSON_TYPE, "githubReconcileRefs", "request", schedule = "0 * * * *")
        seed("github-import-pull-requests", "GitHub: Import Pull Requests", "bosca.git.model.GitHubDelivery", "githubImportPullRequest", "delivery")
        seed("github-export-pull-requests", "GitHub: Export Pull Requests", "bosca.git.model.PullRequestEvent", "githubExportPullRequest", "event")
        seed("github-reconcile-pull-requests", "GitHub: Reconcile Pull Requests", InputNode.JSON_TYPE, "githubReconcilePullRequests", "request", schedule = "0 * * * *")
    }

    private suspend fun seed(key: String, name: String, inputType: String, nodeType: String, inputPort: String, schedule: String? = null) {
        if (pipelines.getByKey(key) != null) return
        pipelines.save(id = UUID.NIL, name = name, description = "Synchronize paired repositories through the GitHub service.",
            key = key, acceptedInputType = inputType, triggered = schedule == null, schedule = schedule, version = 0,
            graph = buildJsonObject {
                put("nodes", JsonArray(listOf(
                    node("input", "input", 60).let { JsonObject(it + ("acceptedType" to kotlinx.serialization.json.JsonPrimitive(inputType))) },
                    node(nodeType, "sync", 340),
                    node("output", "output", 620),
                )))
                put("edges", JsonArray(listOf(
                    edge("input-sync", "input", "sync", inputPort),
                    edge("sync-output", "sync", "output", "in"),
                )))
            })
    }

    private fun node(type: String, id: String, x: Int) = buildJsonObject {
        put("type", type); put("id", id)
        put("position", buildJsonObject { put("x", x); put("y", 160) })
    }

    private fun edge(id: String, source: String, target: String, targetPort: String) = buildJsonObject {
        put("id", id); put("source", source); put("target", target)
        put("sourcePort", "out"); put("targetPort", targetPort)
    }
}

@Providers
class GitHubPackageInstallerRegistry {
    @Provider(name = "github-pipelines")
    fun installer(pipelines: PipelineService): PackageInstaller = GitHubPipelinesInstaller(pipelines)

    @Provider(name = "github")
    fun installation(): PackageInstallation = PackageInstallation(
        key = "github", name = "GitHub",
        versions = listOf(PackageInstallationVersion(version = "1.1.0", installerNames = listOf("github-pipelines"))),
    )
}
