package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Resolves current links through authorized roots before any telemetry query or subscription uses them. */
@Serializable
class WorkOpsReleaseTelemetrySourcesModel(
    val id: String,
    var applications: List<WorkOpsReleaseTelemetrySource> = emptyList(),
    var services: List<WorkOpsReleaseTelemetrySource> = emptyList(),
    var unavailableProjects: Int = 0,
    var loaded: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    suspend fun refresh(ctx: RenderContext) = feedback.perform {
        loaded = false
        val bundled = workOpsReleaseComponents(id, ctx)
        val projects = if (bundled.isEmpty()) emptyMap() else ctx.gql.execute(WorkOpsVersionProjects, Unit).workOps.projects.all.associateBy { it.id }
        val apps = mutableListOf<WorkOpsReleaseTelemetrySource>(); val linkedServices = mutableListOf<WorkOpsReleaseTelemetrySource>()
        var unavailable = 0
        for (projectId in bundled.map { it.projectId }.distinct()) {
            val project = projects[projectId]
            if (project == null) { unavailable++; continue }
            val links = ctx.gql.execute(WorkOpsProjectTelemetry, WorkOpsProjectTelemetry.Variables(projectId)).workOps.projects.project
            if (links == null) { unavailable++; continue }
            val versions = if (links.analyticsApplications.isEmpty()) emptyMap() else
                ctx.gql.execute(WorkOpsDeliveryVersions, WorkOpsDeliveryVersions.Variables(projectId)).workOps.versions.byProject.associateBy { it.id }
            val label = "${project.key} · ${project.name}"
            for (component in bundled.filter { it.projectId == projectId }) {
                apps.addAll(links.analyticsApplications.map { WorkOpsReleaseTelemetrySource(projectId, label, it.applicationId, component.versionId, versions[component.versionId]?.name.orEmpty()) })
            }
            linkedServices.addAll(links.analyticsServices.map { WorkOpsReleaseTelemetrySource(projectId, label, it.service) })
        }
        applications = apps.distinctBy { listOf(it.projectId, it.identifier, it.versionId) }
        services = linkedServices.distinctBy { it.projectId to it.identifier }
        unavailableProjects = unavailable; loaded = true
    }
}
