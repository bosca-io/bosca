package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class WorkOpsWorkflowsModel(
    val kind: String = "workflows",
    var offset: Long = 0,
    val targetId: String = "",
    var workflows: List<WorkOpsWorkflowRecord> = emptyList(),
    var schemes: List<WorkOpsWorkflowSchemeRecord> = emptyList(),
    var loaded: Boolean = false,
    var editing: Boolean = false,
    var draft: WorkOpsWorkflowDraft = WorkOpsWorkflowDraft(),
    var deleting: WorkOpsWorkflowDraft? = null,
    var detail: WorkOpsWorkflowRecord? = null,
    var resultId: String = "",
    var resultKind: String = "workflows",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val showingSchemes get() = kind == "schemes"
    val tabs get() = listOf(NavigationItem("Workflows", href("workflows", 0), !showingSchemes), NavigationItem("Workflow schemes", href("schemes", 0), showingSchemes))
    val title get() = if (showingSchemes) "Workflow schemes" else "Workflows"
    val pageWorkflows get() = workflows.drop(pageOffset).take(25)
    val pageSchemes get() = schemes.drop(pageOffset).take(25)
    private val pageOffset get() = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val count get() = if (showingSchemes) schemes.size else workflows.size
    val previousHref get() = if (offset > 0) href(kind, (offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (count > offset + 25) href(kind, offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(resultKind, 0) + "&item=${workOpsEncode(resultId)}#workops-workflow-${workOpsEncode(resultId)}"
    val workflowChoices get() = workflows.map { SchemaFormOption(it.id, it.name) }.let { choices ->
        if (draft.defaultId.isNotBlank() && choices.none { it.value == draft.defaultId }) choices + SchemaFormOption(draft.defaultId, "Workflow unavailable") else choices
    }
    val editedWorkflow get() = workflows.firstOrNull { it.id == draft.id && draft.kind == "workflows" }
    fun workflowName(id: String) = workflows.firstOrNull { it.id == id }?.name ?: "Workflow unavailable"
    private fun href(section: String, page: Long) = "/workops/settings/workflows?tab=$section&offset=$page"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val data = ctx.gql.execute(WorkOpsWorkflowCatalog, Unit).workOps.workflows
        workflows = data.all.map { flow -> WorkOpsWorkflowRecord(flow.id, flow.name, flow.description.orEmpty(), flow.initialStateId.orEmpty(), flow.version,
            flow.states.map { WorkOpsWorkflowStateRecord(it.id, it.status.name, it.status.category.name, it.status.colorHex, it.displayOrder) },
            flow.transitions.map { WorkOpsWorkflowTransitionRecord(it.id, it.name, it.description.orEmpty(), it.fromStateIds, it.toStateId) }) }
        schemes = data.schemes.map { WorkOpsWorkflowSchemeRecord(it.id, it.name, it.description.orEmpty(), it.defaultWorkflowId, it.version) }
        if (targetId.isNotBlank()) (if (showingSchemes) schemes.map { it.id } else workflows.map { it.id }).indexOf(targetId).takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open(section: String, id: String) {
        require(loaded) { "Reload workflows first." }
        require(section in setOf("workflows", "schemes")) { "Choose workflows or schemes." }
        draft = if (id.isBlank()) WorkOpsWorkflowDraft(kind = section, defaultId = if (section == "schemes") workflows.firstOrNull()?.id.orEmpty() else "")
        else if (section == "schemes") requireNotNull(schemes.firstOrNull { it.id == id }) { "Reload this scheme." }.let { WorkOpsWorkflowDraft(section, it.id, it.name, it.description, it.defaultId, it.version) }
        else requireNotNull(workflows.firstOrNull { it.id == id }) { "Reload this workflow." }.let { WorkOpsWorkflowDraft(section, it.id, it.name, it.description, version = it.version) }
        detail = null; editing = true; resultId = ""; feedback.message = ""; feedback.failed = false
    }
    fun cancel() { editing = false }
    fun view(id: String) {
        require(loaded) { "Reload workflows first." }
        detail = requireNotNull(workflows.firstOrNull { it.id == id }) { "Reload this workflow." }
    }
    fun closeDetail() { detail = null }
    fun editDetail() { val id = requireNotNull(detail).id; open("workflows", id) }
    private fun capture(name: String, description: String, defaultId: String) { draft = draft.copy(name = name, description = description, defaultId = defaultId) }
    suspend fun reloadDraft(name: String, description: String, defaultId: String, ctx: RenderContext) = feedback.perform { capture(name, description, defaultId); reload(ctx) }
    suspend fun save(name: String, description: String, defaultId: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, defaultId)
        require(editing && loaded) { "Reload workflows before saving." }
        require(name.isNotBlank()) { "Enter a name." }
        val notes = description.trim().takeIf(String::isNotBlank)
        val id = draft.id
        resultId = if (draft.kind == "schemes") {
            require(defaultId.isNotBlank()) { "Choose a default workflow." }
            if (id.isBlank()) ctx.gql.execute(WorkOpsWorkflowSchemeCreate, WorkOpsWorkflowSchemeCreate.Variables(CreateWorkOpsWorkflowSchemeInput(name.trim(), notes, defaultId))).workOps.workflows.createScheme.id
            else ctx.gql.execute(WorkOpsWorkflowSchemeUpdate, WorkOpsWorkflowSchemeUpdate.Variables(id, UpdateWorkOpsWorkflowSchemeInput(name.trim(), notes, defaultId, draft.version))).workOps.workflows.updateScheme.id
        } else {
            if (id.isBlank()) ctx.gql.execute(WorkOpsWorkflowCreate, WorkOpsWorkflowCreate.Variables(CreateWorkOpsWorkflowInput(name.trim(), notes))).workOps.workflows.create.id
            else ctx.gql.execute(WorkOpsWorkflowUpdate, WorkOpsWorkflowUpdate.Variables(id, UpdateWorkOpsWorkflowInput(name.trim(), notes, draft.version))).workOps.workflows.update.id
        }
        resultKind = draft.kind; editing = false; reload(ctx); feedback.message = "${draft.label.replaceFirstChar(Char::uppercase)} saved."
    }
    fun beginDelete(section: String, id: String) { open(section, id); editing = false; deleting = draft }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleting) { "Choose a workflow or scheme first." }
        require(confirmed) { "Confirm deletion." }
        val deleted = if (target.kind == "schemes") ctx.gql.execute(WorkOpsWorkflowSchemeDelete, WorkOpsWorkflowSchemeDelete.Variables(target.id)).workOps.workflows.deleteScheme
        else ctx.gql.execute(WorkOpsWorkflowDelete, WorkOpsWorkflowDelete.Variables(target.id)).workOps.workflows.delete
        check(deleted) { "The ${target.label} was not deleted." }
        deleting = null; if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "${target.label.replaceFirstChar(Char::uppercase)} deleted."
    }
}

suspend fun workOpsWorkflows(): WorkOpsWorkflowsModel {
    val ctx = currentRenderContext()
    return WorkOpsWorkflowsModel(if (ctx.query["tab"] == "schemes") "schemes" else "workflows", ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}
