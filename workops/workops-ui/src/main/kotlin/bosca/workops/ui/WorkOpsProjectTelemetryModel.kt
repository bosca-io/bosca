package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Application and service configuration use independent islands and retain their own drafts. */
@Serializable
class WorkOpsProjectTelemetryModel(
    val projectId: String,
    val kind: String,
    var offset: Long = 0,
    val targetId: String = "",
    val otherOffset: Long = 0,
    var rows: List<WorkOpsProjectIdentifier> = emptyList(),
    var loaded: Boolean = false,
    var draft: String = "",
    var deleteTarget: WorkOpsProjectIdentifier? = null,
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val title get() = if (kind == "applications") "Analytics applications" else "Analytics services"
    val label get() = if (kind == "applications") "Application identifier" else "Service name"
    val offsetKey get() = if (kind == "applications") "applicationOffset" else "serviceOffset"
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    private fun href(page: Long): String {
        val applications = if (kind == "applications") page else otherOffset
        val services = if (kind == "services") page else otherOffset
        return "/workops/projects/${workOpsEncode(projectId)}?tab=analytics&applicationOffset=$applications&serviceOffset=$services"
    }
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size.toLong() > offset + 25) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&kind=$kind&item=${workOpsEncode(resultId)}#workops-analytics-$kind-${workOpsEncode(resultId)}"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        require(kind in setOf("applications", "services")) { "Unknown telemetry identifier kind." }
        val data = ctx.gql.execute(WorkOpsProjectTelemetry, WorkOpsProjectTelemetry.Variables(projectId)).workOps.projects.project
        if (data == null) { rows = emptyList(); error("This project is unavailable.") }
        rows = if (kind == "applications") data.analyticsApplications.map { WorkOpsProjectIdentifier(it.id, it.applicationId) }
            else data.analyticsServices.map { WorkOpsProjectIdentifier(it.id, it.service) }
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(value: String, ctx: RenderContext) = feedback.perform { draft = value; reload(ctx) }
    suspend fun add(value: String, ctx: RenderContext) = feedback.perform {
        draft = value
        require(loaded && kind in setOf("applications", "services")) { "Reload the identifiers first." }
        require(value.isNotBlank()) { "Enter a ${label.lowercase()}." }
        resultId = if (kind == "applications") ctx.gql.execute(WorkOpsAddProjectApplication, WorkOpsAddProjectApplication.Variables(projectId, value.trim()))
            .workOps.projects.addAnalyticsApplication.id
        else ctx.gql.execute(WorkOpsAddProjectService, WorkOpsAddProjectService.Variables(projectId, value.trim())).workOps.projects.addAnalyticsService.id
        draft = ""; reload(ctx); feedback.message = "Identifier added."
    }
    fun beginDelete(id: String, value: String) {
        draft = value
        require(loaded) { "Reload the identifiers first." }
        deleteTarget = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this identifier." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleteTarget = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleteTarget) { "Choose an identifier first." }
        require(confirmed) { "Confirm removal." }
        require(kind in setOf("applications", "services")) { "Unknown telemetry identifier kind." }
        if (kind == "applications") ctx.gql.execute(WorkOpsRemoveProjectApplication, WorkOpsRemoveProjectApplication.Variables(projectId, target.id))
        else ctx.gql.execute(WorkOpsRemoveProjectService, WorkOpsRemoveProjectService.Variables(projectId, target.id))
        deleteTarget = null
        if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "Identifier removed."
    }
}

suspend fun workOpsProjectTelemetry(projectId: String, kind: String): WorkOpsProjectTelemetryModel {
    val ctx = currentRenderContext()
    val key = if (kind == "applications") "applicationOffset" else "serviceOffset"
    val other = if (kind == "applications") "serviceOffset" else "applicationOffset"
    return WorkOpsProjectTelemetryModel(projectId, kind, ctx.query[key]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["item"].orEmpty().takeIf { ctx.query["kind"] == kind }.orEmpty(),
        ctx.query[other]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { it.feedback.perform { it.reload(ctx) } }
}
