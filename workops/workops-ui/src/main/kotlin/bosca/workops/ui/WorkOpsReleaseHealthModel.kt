package bosca.workops.ui

import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import kotlinx.serialization.Serializable

/** Deferred health reads retain prior data as stale and never query telemetry for unresolved sources. */
@Serializable
class WorkOpsReleaseHealthModel(
    val id: String,
    var readContext: String = "",
    val sources: WorkOpsReleaseTelemetrySourcesModel = WorkOpsReleaseTelemetrySourcesModel(id),
    var applications: List<WorkOpsReleaseAppHealth> = emptyList(),
    var services: List<WorkOpsReleaseServiceHealth> = emptyList(),
) {
    /** An empty revision retains the current tag for a user refresh queued behind a newer context read. */
    suspend fun refreshContext(revision: String, ctx: RenderContext) {
        applications.forEach { it.sessionsLoaded = false; it.errorsLoaded = false }
        services.forEach { it.loaded = false }
        sources.refresh(ctx)
        if (sources.loaded) {
            applications = sources.applications.groupBy { it.identifier }.map { (identifier, links) ->
                (applications.firstOrNull { it.identifier == identifier } ?: WorkOpsReleaseAppHealth(identifier)).also { it.projects = links.map { link -> link.projectLabel }.distinct() }
            }
            services = sources.services.groupBy { it.identifier }.map { (identifier, links) ->
                (services.firstOrNull { it.identifier == identifier } ?: WorkOpsReleaseServiceHealth(identifier)).also { it.projects = links.map { link -> link.projectLabel }.distinct() }
            }
            for (application in applications) application.refresh(ctx)
            for (service in services) service.refresh(ctx)
        }
        if (revision.isNotBlank()) readContext = revision
    }
}

suspend fun workOpsReleaseHealth(id: String, revision: String) = WorkOpsReleaseHealthModel(id, revision).also { it.refreshContext(revision, currentRenderContext()) }
