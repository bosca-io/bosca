package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Bundle facts are program-visible; display labels come from authorized project/version roots. */
@Serializable
data class WorkOpsReleaseComponentRecord(
    val projectId: String,
    val versionId: String,
    val order: Int? = null,
    val status: String = "",
    val deployed: String = "",
    val rollbackVersionId: String = "",
    val projectKey: String = "",
    val projectName: String = "",
    val versionName: String = "",
) {
    val projectLabel get() = if (projectName.isBlank()) "Project unavailable" else "$projectKey · $projectName"
    val versionLabel get() = versionName.ifBlank { "Version unavailable" }
    val href get() = if (projectName.isBlank()) "" else "/workops/projects/${workOpsEncode(projectId)}"
    val tone get() = when (status.uppercase()) {
        "DEPLOYED" -> "success"
        "DEPLOYING" -> "info"
        "FAILED" -> "error"
        "ROLLED_BACK" -> "warning"
        else -> "neutral"
    }
}

internal suspend fun workOpsReleaseComponents(id: String, ctx: RenderContext): List<WorkOpsReleaseComponentRecord> =
    ctx.gql.execute(WorkOpsReleaseComponents, WorkOpsReleaseComponents.Variables(id)).workOps.crossProject.versionsForRelease
        .map { WorkOpsReleaseComponentRecord(it.projectId, it.versionId, it.deploymentOrder, it.deploymentStatus, it.deployedAt.orEmpty(), it.rollbackVersionId.orEmpty()) }
        .sortedWith(compareBy<WorkOpsReleaseComponentRecord> { it.order ?: 0 })

internal suspend fun workOpsReleaseComponentLabels(rows: List<WorkOpsReleaseComponentRecord>, ctx: RenderContext): List<WorkOpsReleaseComponentRecord> {
    if (rows.isEmpty()) return rows
    val projects = ctx.gql.execute(WorkOpsVersionProjects, Unit).workOps.projects.all.associateBy { it.id }
    var labeled = rows.map { row -> projects[row.projectId]?.let { row.copy(projectKey = it.key, projectName = it.name) } ?: row }
    for (project in rows.map { it.projectId }.distinct().filter { it in projects }) {
        val versions = ctx.gql.execute(WorkOpsDeliveryVersions, WorkOpsDeliveryVersions.Variables(project)).workOps.versions.byProject.associateBy { it.id }
        labeled = labeled.map { row -> if (row.projectId == project) row.copy(versionName = versions[row.versionId]?.name.orEmpty()) else row }
    }
    return labeled
}
