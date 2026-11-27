package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Project versions, components and labels compose the same WorkOps list/dialog controls. */
@Serializable
class WorkOpsProjectCatalogModel(
    val projectId: String,
    val kind: String,
    var offset: Long = 0,
    val targetId: String = "",
    var rows: List<WorkOpsProjectCatalogRow> = emptyList(),
    var loaded: Boolean = false,
    var creating: Boolean = false,
    var nameDraft: String = "",
    var descriptionDraft: String = "",
    var modeDraft: String = "UNASSIGNED",
    var colorDraft: String = "#a78bff",
    var deleteTarget: WorkOpsProjectCatalogRow? = null,
    var releaseTarget: WorkOpsProjectCatalogRow? = null,
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val delivery: Boolean = false,
    var startDateDraft: String = "",
    var releaseDateDraft: String = "",
    val settings: Boolean = false,
    var pageDirectory: WorkOpsProjectDirectoryModel? = null,
) {
    val title get() = kind.replaceFirstChar(Char::uppercase)
    val entityLabel get() = when (kind) { "versions" -> "version"; "components" -> "component"; else -> "label" }
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val hasNext get() = rows.size.toLong() > offset + 25
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (hasNext) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&item=${workOpsEncode(resultId)}#workops-project-$kind-${workOpsEncode(resultId)}"
    val modes get() = WorkOpsComponentAssigneeMode.entries.map { SchemaFormOption(it.name, it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) }
    private fun href(page: Long) = if (settings) "/workops/settings/components?projectId=${workOpsEncode(projectId)}&offset=$page"
        else if (delivery) "/workops/releases/versions?projectId=${workOpsEncode(projectId)}&offset=$page"
        else "/workops/projects/${workOpsEncode(projectId)}?tab=$kind&offset=$page"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        require(kind in setOf("versions", "components", "labels")) { "Unknown project catalog." }
        require(!delivery || kind == "versions") { "Choose the delivery versions catalog." }
        require(!settings || kind == "components") { "Choose the component settings catalog." }
        rows = if (delivery) ctx.gql.execute(WorkOpsDeliveryVersions, WorkOpsDeliveryVersions.Variables(projectId))
            .workOps.versions.byProject.map { it.projectCatalogRow() }.sortedByDescending { it.sequenceNumber }
        else ctx.gql.execute(WorkOpsProjectCatalogs, WorkOpsProjectCatalogs.Variables(projectId)).workOps.let { values -> when (kind) {
            "versions" -> values.versions.byProject.map { it.projectCatalogRow() }
            "components" -> values.components.byProject.map { it.projectCatalogRow() }
            else -> values.labels.byProject.map { it.projectCatalogRow() }
        } }
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun reloadDraft(name: String, description: String, mode: String, color: String, startDate: String, releaseDate: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name; descriptionDraft = description; modeDraft = mode; colorDraft = color
        startDateDraft = startDate; releaseDateDraft = releaseDate
        reload(ctx)
    }
    fun open() { creating = true; nameDraft = ""; descriptionDraft = ""; modeDraft = "UNASSIGNED"; colorDraft = "#a78bff"; startDateDraft = ""; releaseDateDraft = ""; resultId = ""; feedback.message = ""; feedback.failed = false }
    fun cancelCreate() { creating = false }
    suspend fun create(name: String, description: String, mode: String, color: String, startDate: String, releaseDate: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name; descriptionDraft = description; modeDraft = mode; colorDraft = color
        startDateDraft = startDate; releaseDateDraft = releaseDate
        require(creating && loaded && kind in setOf("versions", "components", "labels")) { "Reload the catalog before creating a $entityLabel." }
        require(name.isNotBlank()) { "Enter a name." }
        val details = description.trim().takeIf(String::isNotBlank)
        resultId = when (kind) {
            "versions" -> ctx.gql.execute(WorkOpsCreateProjectVersion, WorkOpsCreateProjectVersion.Variables(
                CreateWorkOpsVersionInput(projectId, name.trim(), details,
                    startDate = if (delivery) workOpsUtcDate(startDate) else null,
                    releaseDate = if (delivery) workOpsUtcDate(releaseDate) else null))).workOps.versions.create.id
            "components" -> {
                val selected = WorkOpsComponentAssigneeMode.entries.firstOrNull { it.name == mode }
                requireNotNull(selected) { "Choose an assignee mode." }
                ctx.gql.execute(WorkOpsCreateProjectComponent, WorkOpsCreateProjectComponent.Variables(
                    CreateWorkOpsComponentInput(projectId, name.trim(), details, selected))).workOps.components.create.id
            }
            else -> ctx.gql.execute(WorkOpsCreateProjectLabel, WorkOpsCreateProjectLabel.Variables(
                CreateWorkOpsLabelInput(name.trim(), color.takeIf(String::isNotBlank), WorkOpsLabelScope.PROJECT, projectId = projectId))).workOps.labels.create.id
        }
        creating = false
        if (delivery) offset = 0
        reload(ctx)
        feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} created."
    }
    fun beginDelete(id: String) {
        require(loaded && (kind in setOf("components", "labels") || delivery && kind == "versions")) { "Reload the project catalog first." }
        deleteTarget = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this $entityLabel." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleteTarget = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleteTarget) { "Choose a $entityLabel first." }
        require(confirmed) { "Confirm deletion." }
        when (kind) {
            "components" -> ctx.gql.execute(WorkOpsDeleteProjectComponent, WorkOpsDeleteProjectComponent.Variables(target.id))
            "labels" -> ctx.gql.execute(WorkOpsDeleteProjectLabel, WorkOpsDeleteProjectLabel.Variables(target.id))
            "versions" -> {
                require(delivery) { "Open delivery versions to delete a version." }
                ctx.gql.execute(WorkOpsDeleteProjectVersion, WorkOpsDeleteProjectVersion.Variables(target.id))
            }
            else -> error("This catalog does not support deletion here.")
        }
        deleteTarget = null
        if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} deleted."
    }
    fun beginRelease(id: String) {
        require(loaded && kind == "versions") { "Reload project versions first." }
        releaseTarget = requireNotNull(rows.firstOrNull { it.id == id && !it.released && (delivery || !it.archived) }) { "Choose an unreleased version." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelRelease() { releaseTarget = null }
    suspend fun release(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(releaseTarget) { "Choose a version first." }
        require(confirmed) { "Confirm the release." }
        ctx.gql.execute(WorkOpsReleaseProjectVersion, WorkOpsReleaseProjectVersion.Variables(target.id, target.version))
        releaseTarget = null; reload(ctx); feedback.message = "Version released."
    }
}

suspend fun workOpsProjectCatalog(projectId: String, kind: String): WorkOpsProjectCatalogModel {
    val ctx = currentRenderContext()
    return WorkOpsProjectCatalogModel(projectId, kind, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}

suspend fun workOpsDeliveryVersionCatalog(projectId: String): WorkOpsProjectCatalogModel {
    val ctx = currentRenderContext()
    return WorkOpsProjectCatalogModel(projectId, "versions", ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["item"].orEmpty(), delivery = true).also { it.retry(ctx) }
}

suspend fun workOpsComponentCatalog(projectId: String): WorkOpsProjectCatalogModel {
    val ctx = currentRenderContext()
    return WorkOpsProjectCatalogModel(projectId, "components", ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["item"].orEmpty(), settings = true).also { it.retry(ctx) }
}
