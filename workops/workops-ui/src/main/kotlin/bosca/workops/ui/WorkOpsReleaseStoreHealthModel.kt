package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.WorkOpsReleaseStoreTelemetry
import bosca.workops.ui.graphql.WorkOpsVersionProjects
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** External store I/O is deferred; failed reads retain one explicitly stale, atomic snapshot. */
@Serializable
class WorkOpsReleaseStoreHealthModel(
    val id: String,
    var readContext: String = "",
    var rows: List<WorkOpsReleaseStoreHealthRow> = emptyList(),
    var loaded: Boolean = false,
    var checked: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    /** Refreshes current release state and keeps the last successful read tagged to its parent context. */
    suspend fun refreshContext(revision: String, ctx: RenderContext) {
        feedback.perform {
            loaded = false
            val telemetry = ctx.gql.execute(WorkOpsReleaseStoreTelemetry, WorkOpsReleaseStoreTelemetry.Variables(id)).workOps.multiRepo.releaseStoreTelemetry
            val projects = if (telemetry.states.isEmpty()) emptyMap() else
                ctx.gql.execute(WorkOpsVersionProjects, Unit).workOps.projects.all.associateBy { it.id }
            val observations = telemetry.observations.map { observation -> WorkOpsStoreObservation(
                observation.store, observation.applicationId, observation.appVersion, observation.telemetryType,
                OffsetDateTime.parse(observation.observedAt).toInstant().toString(), observation.payload,
            ) }.sortedByDescending { it.instant }
            val updated = telemetry.states.map { state -> WorkOpsReleaseStoreHealthRow(
                state,
                projects[state.projectId]?.let { "${it.key} · ${it.name}" } ?: "Project unavailable",
                observations.filter { it.store == state.store && it.applicationId == state.applicationId && it.appVersion == state.appVersion },
            ) }
            rows = updated; loaded = true; checked = OffsetDateTime.now(ZoneOffset.UTC).toString()
        }
        if (revision.isNotBlank()) readContext = revision
    }
}

suspend fun workOpsReleaseStoreHealth(id: String, revision: String) = WorkOpsReleaseStoreHealthModel(id, revision).also { it.refreshContext(revision, currentRenderContext()) }
