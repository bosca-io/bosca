package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Both requirement parent kinds use aggregate creation and the same table, assignment and deletion actions. */
@Serializable
data class WorkOpsRequirementsModel(
    val parentId: String,
    val parentHref: String,
    val offset: Long = 0,
    var data: List<WorkOpsRequirementRow> = emptyList(),
    var names: Map<String, String> = emptyMap(),
    var creating: Boolean = false,
    var nameDraft: String = "",
    var editingId: String = "",
    var assigneeDraft: String = "",
    var assigneeName: String = "",
    var editVersion: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    var createdId: String = "",
    val parentType: WorkOpsRequirementParent = WorkOpsRequirementParent.TASK,
    var revision: Long = 0,
) {
    val rows get() = data.take(25)
    val doneCount get() = rows.count { it.category == WorkOpsStatusCategory.DONE }
    val previousHref get() = if (offset > 0) pageHref((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (data.size > 25) pageHref(offset + 25) else ""
    private fun pageHref(offset: Long) = parentHref + (if ('?' in parentHref) "&" else "?") + "requirementsOffset=$offset"
    fun name(metadataId: String) = names[metadataId] ?: "Requirement unavailable"

    suspend fun reload(ctx: RenderContext) {
        data = if (parentType == WorkOpsRequirementParent.SPEC) {
            ctx.gql.execute(WorkOpsRequirementsForSpec, WorkOpsRequirementsForSpec.Variables(parentId, offset)).workOps.requirements.bySpec.map {
                WorkOpsRequirementRow(it.id, it.metadataId, it.version, it.sortOrder, it.status.name, it.status.category,
                    it.priority.name, it.priority.colorHex, it.assigneeProfileId, it.assignee?.name)
            }
        } else {
            ctx.gql.execute(WorkOpsRequirementsForTask, WorkOpsRequirementsForTask.Variables(parentId, offset)).workOps.requirements.byTask.map {
                WorkOpsRequirementRow(it.id, it.metadataId, it.version, it.sortOrder, it.status.name, it.status.category,
                    it.priority.name, it.priority.colorHex, it.assigneeProfileId, it.assignee?.name)
            }
        }
        val names = mutableMapOf<String, String>()
        for (row in rows) {
            ctx.gql.execute(WorkOpsRequirementName, WorkOpsRequirementName.Variables(row.metadataId)).content.metadata?.let {
                names[row.metadataId] = workOpsContentLabel(it.name, row.metadataId, "Requirement")
            }
        }
        this.names = names
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun beginCreate() { creating = true; createdId = "" }
    fun cancelCreate() { creating = false }
    suspend fun create(name: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name
        require(creating && name.isNotBlank()) { "Enter a requirement name." }
        createdId = ctx.gql.execute(WorkOpsCreateRequirement, WorkOpsCreateRequirement.Variables(CreateWorkOpsRequirementInput(
            name = name.trim(), parentType = parentType, parentId = parentId,
            sortOrder = rows.lastOrNull()?.sortOrder?.let { it + 1 } ?: 0))).workOps.requirements.create.id
        revision++
        creating = false; nameDraft = ""
        reload(ctx)
        feedback.message = "Requirement created."
    }
    fun editAssignee(id: String) {
        rows.firstOrNull { it.id == id }?.let {
            editingId = id; assigneeDraft = it.assigneeId.orEmpty(); editVersion = it.version
            assigneeName = workOpsAssigneeLabel(it.assigneeId, it.assignee)
        }
    }
    fun cancelAssignee() { editingId = "" }
    suspend fun assign(profileId: String, ctx: RenderContext) = feedback.perform {
        assigneeDraft = profileId
        val row = requireNotNull(rows.firstOrNull { it.id == editingId }) { "Choose a requirement first." }
        if (profileId.isNotBlank() && profileId != row.assigneeId) {
            requireNotNull(ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(profileId)).profiles.profile) {
                "The selected assignee is unavailable."
            }
        }
        ctx.gql.execute(WorkOpsUpdateRequirement, WorkOpsUpdateRequirement.Variables(editingId,
            UpdateWorkOpsRequirementInput(assigneeProfileId = profileId.takeIf(String::isNotBlank), clearAssignee = profileId.isBlank(), expectedVersion = editVersion)))
        editingId = ""
        reload(ctx)
        feedback.message = "Assignee updated."
    }
    suspend fun delete(id: String, version: Long, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed && rows.any { it.id == id && it.version == version }) { "Confirm deletion of a current requirement." }
        ctx.gql.execute(WorkOpsDeleteRequirement, WorkOpsDeleteRequirement.Variables(id, version))
        revision++
        reload(ctx)
        feedback.message = "Requirement deleted."
    }
}

suspend fun workOpsRequirements(parentId: String, parentHref: String, parentType: String): WorkOpsRequirementsModel {
    val ctx = currentRenderContext()
    return WorkOpsRequirementsModel(parentId, parentHref, ctx.query["requirementsOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        parentType = WorkOpsRequirementParent.valueOf(parentType))
        .also { model -> model.feedback.perform { model.reload(ctx) } }
}
