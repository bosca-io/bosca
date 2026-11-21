package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Saved queries remain owned by the caller's profile and are validated by the existing BQL API. */
@Serializable
class WorkOpsFiltersModel(
    val offset: Long = 0,
    var rows: List<WorkOpsSavedFilterRow> = emptyList(),
    var hasNext: Boolean = false,
    var editing: Boolean = false,
    var draft: WorkOpsSavedFilterDraft = WorkOpsSavedFilterDraft(),
    var diagnostics: List<WorkOpsBqlDiagnostic> = emptyList(),
    var previewId: String = "",
    var previewSource: String = "",
    var previewRows: List<WorkOpsFilterPreviewTask> = emptyList(),
    var deletingId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val page get() = offset / 50 + 1
    val previousHref get() = if (offset > 0) "/workops/filters?offset=${(offset - 50).coerceAtLeast(0)}" else ""
    val nextHref get() = if (hasNext) "/workops/filters?offset=${offset + 50}" else ""
    val editReturn get() = if (draft.id.isBlank()) "#workops-filter-new" else "#workops-filter-edit-${draft.id}"
    val previewReturn get() = "#workops-filter-preview-$previewId"
    val previewHref get() = "/workops/tasks?bql=${workOpsEncode(previewSource)}"
    val deleteName get() = rows.firstOrNull { it.id == deletingId }?.name.orEmpty()

    suspend fun reload(ctx: RenderContext) {
        val values = ctx.gql.execute(WorkOpsSavedFiltersPage, WorkOpsSavedFiltersPage.Variables(offset)).workOps.savedFilters.mine
        rows = values.take(50).map { WorkOpsSavedFilterRow(it.id, it.name, it.description.orEmpty(), it.bqlSource, it.modifiedAt, it.version) }
        hasNext = values.size > 50
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun create() { draft = WorkOpsSavedFilterDraft(); diagnostics = emptyList(); feedback.message = ""; feedback.failed = false; editing = true }
    fun edit(id: String) {
        val row = rows.firstOrNull { it.id == id } ?: return
        draft = WorkOpsSavedFilterDraft(row.id, row.name, row.description, row.source, row.version)
        diagnostics = emptyList(); feedback.message = ""; feedback.failed = false; editing = true
    }
    fun cancelEdit() { editing = false }

    private suspend fun validate(source: String, ctx: RenderContext): Boolean {
        diagnostics = ctx.gql.execute(WorkOpsValidateBql, WorkOpsValidateBql.Variables(source)).workOps.savedFilters.validateBql.errors
            .map { WorkOpsBqlDiagnostic(it.message, it.start, it.end, it.hint.orEmpty()) }
        return diagnostics.isEmpty()
    }
    suspend fun save(name: String, description: String, source: String, ctx: RenderContext) = feedback.perform {
        draft = draft.copy(name = name, description = description, source = source)
        require(editing) { "Open the saved filter editor first." }
        require(name.isNotBlank() && source.isNotBlank()) { "Enter a name and BQL query." }
        if (!validate(source, ctx)) return@perform
        val input = WorkOpsSavedFilterInput(name.trim(), description.takeIf(String::isNotBlank), source)
        if (draft.id.isBlank()) ctx.gql.execute(WorkOpsCreateSavedFilter, WorkOpsCreateSavedFilter.Variables(input))
        else ctx.gql.execute(WorkOpsUpdateSavedFilter, WorkOpsUpdateSavedFilter.Variables(draft.id, input, draft.version))
        editing = false
        reload(ctx)
        feedback.message = "Saved filter saved."
    }
    suspend fun preview(id: String, ctx: RenderContext) = feedback.perform {
        val row = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload the saved filters." }
        previewId = id; previewSource = row.source; previewRows = emptyList()
        if (!validate(row.source, ctx)) return@perform
        previewRows = ctx.gql.execute(WorkOpsSavedFilterPreview, WorkOpsSavedFilterPreview.Variables(row.source)).workOps.savedFilters.searchTasks.rows
            .map { WorkOpsFilterPreviewTask(it.id, it.key, it.summary, it.status.name, it.status.category.name) }
    }
    fun cancelPreview() { previewId = "" }
    fun beginDelete(id: String) { if (rows.any { it.id == id }) { deletingId = id; feedback.message = ""; feedback.failed = false } }
    fun cancelDelete() { deletingId = "" }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(deletingId.isNotBlank() && confirmed) { "Confirm saved filter deletion." }
        ctx.gql.execute(WorkOpsDeleteSavedFilter, WorkOpsDeleteSavedFilter.Variables(deletingId))
        deletingId = ""
        reload(ctx)
        feedback.message = "Saved filter deleted."
    }
}

suspend fun workOpsFilters(): WorkOpsFiltersModel {
    val ctx = currentRenderContext()
    return WorkOpsFiltersModel(ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { it.retry(ctx) }
}
