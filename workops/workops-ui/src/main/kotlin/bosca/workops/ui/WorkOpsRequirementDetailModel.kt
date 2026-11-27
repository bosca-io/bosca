package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A requirement's workflow belongs to its linked task; its rich body belongs to Content. */
@Serializable
data class WorkOpsRequirementDetailModel(
    var id: String,
    val tab: String = "comments",
    val offset: Long = 0,
    var data: WorkOpsRequirementDetailData? = null,
    var document: WorkOpsDocumentModel? = null,
    var parentName: String = "",
    var profileNames: Map<String, String> = emptyMap(),
    var editing: Boolean = false,
    var priorityDraft: String = "",
    var assigneeDraft: String = "",
    var editVersion: Long = 0,
    var renaming: Boolean = false,
    var nameDraft: String = "",
    var pendingTransition: String = "",
    var transitionVersion: Long = 0,
    var commentDraft: String = "",
    var commentRevision: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val requirement get() = data?.workOps?.requirements?.requirement
    val href get() = "/workops/requirements/${workOpsEncode(id)}"
    val title get() = document?.data?.content?.metadata?.let { workOpsContentLabel(it.name, requirement?.metadataId.orEmpty(), "Requirement") }
        ?: if (requirement != null) "Requirement" else if (feedback.failed) "Could not load requirement" else "Requirement unavailable"
    val parentHref get() = requirement?.let {
        "/workops/${if (it.parentType == WorkOpsRequirementParent.SPEC) "specs" else "tasks"}/${workOpsEncode(it.parentId)}"
    }.orEmpty()
    val parentLabel get() = if (requirement?.parentType == WorkOpsRequirementParent.SPEC) "Parent specification" else "Parent task"
    val assigneeLabel get() = workOpsAssigneeLabel(requirement?.assigneeProfileId, requirement?.assignee?.name)
    val lifecycleTaskName get() = requirement?.task?.let { workOpsContentLabel(it.summary, it.id, "Lifecycle task") } ?: "Task unavailable"
    val statusTone get() = if (requirement?.status?.category == WorkOpsStatusCategory.DONE) "success" else "neutral"
    val tabs get() = listOf("comments" to "Comments", "history" to "History")
        .map { (value, label) -> NavigationItem(label, "$href?tab=$value", value == tab) }
    val comments get() = data?.workOps?.requirementComments?.forRequirement.orEmpty()
    val hasNext get() = if (tab == "history") requirement?.history.orEmpty().size > 25 else comments.size > 25
    val previousHref get() = if (offset > 0) "$href?tab=$tab&offset=${(offset - 25).coerceAtLeast(0)}" else ""
    val nextHref get() = if (hasNext) "$href?tab=$tab&offset=${offset + 25}" else ""

    fun profileName(id: String?) = if (id == null) "System" else profileNames[id] ?: "Profile unavailable"

    fun historyField(key: String) = when (key) {
        "created" -> "Requirement"
        "task_id" -> "Lifecycle task"
        "parent_id" -> "Parent"
        "metadata_id" -> "Document"
        "status_id" -> "Status"
        "priority_id" -> "Priority"
        "assignee_profile_id" -> "Assignee"
        else -> key.replace('_', ' ')
    }

    fun historyValue(key: String, value: JsonElement?): String {
        if (value == null || value == JsonNull) return "Unset"
        val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        return when (key) {
            "created" -> "Created"
            "task_id" -> if (requirement?.task?.id == text) lifecycleTaskName else "Task unavailable"
            "parent_id" -> if (text == requirement?.parentId) parentName.ifBlank { "Parent unavailable" } else "Previous parent"
            "metadata_id" -> if (text == requirement?.metadataId) title else "Previous document"
            "status_id" -> data?.workOps?.statuses?.all.orEmpty().firstOrNull { it.id == text }?.name ?: "Status unavailable"
            "priority_id" -> data?.workOps?.tasks?.priorities.orEmpty().firstOrNull { it.id == text }?.name ?: "Priority unavailable"
            "assignee_profile_id" -> profileName(text)
            else -> text
        }
    }

    suspend fun reload(ctx: RenderContext) {
        data = ctx.gql.execute(WorkOpsRequirementDetail, WorkOpsRequirementDetail.Variables(id, offset))
        val requirement = requirement ?: run { document = null; ctx.notFound = true; return }
        document = (document?.takeIf { it.metadataId == requirement.metadataId } ?: WorkOpsDocumentModel(requirement.metadataId))
            .also { it.reload(ctx) }
        parentName = if (requirement.parentType == WorkOpsRequirementParent.SPEC) {
            ctx.gql.execute(WorkOpsRequirementParentSpec, WorkOpsRequirementParentSpec.Variables(requirement.parentId))
                .workOps.specs.spec?.metadata?.name?.let { workOpsContentLabel(it, requirement.parentId, "Specification") }
        } else {
            ctx.gql.execute(WorkOpsRequirementParentTask, WorkOpsRequirementParentTask.Variables(requirement.parentId))
                .workOps.tasks.task?.summary?.let { workOpsContentLabel(it, requirement.parentId, "Parent task") }
        } ?: "$parentLabel unavailable"
        val names = mutableMapOf<String, String>()
        data?.profiles?.current.orEmpty().forEach { names[it.id] = it.name }
        requirement.assignee?.let { names[it.id] = it.name }
        comments.mapNotNull { it.profile }.forEach { names[it.id] = it.name }
        val previousAssignees = requirement.history.flatMap { entry -> entry.changes.filter { it.fieldKey == "assignee_profile_id" }
            .flatMap { listOfNotNull((it.fromValue as? JsonPrimitive)?.contentOrNull, (it.toValue as? JsonPrimitive)?.contentOrNull) } }
        for (profileId in (requirement.history.mapNotNull { it.changedByProfileId } + previousAssignees).distinct().filterNot(names::containsKey)) {
            ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(profileId)).profiles.profile?.let { names[it.id] = it.name }
        }
        profileNames = names
    }

    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }

    fun edit() {
        requirement?.let {
            priorityDraft = it.priority.id; assigneeDraft = it.assigneeProfileId.orEmpty(); editVersion = it.version
            editing = true
        }
    }
    fun cancelEdit() { editing = false }

    suspend fun save(priorityId: String, assigneeId: String, ctx: RenderContext) = feedback.perform {
        priorityDraft = priorityId; assigneeDraft = assigneeId
        require(editing && requirement != null) { "Open the requirement editor before saving." }
        require(data?.workOps?.tasks?.priorities.orEmpty().any { it.id == priorityId }) { "Choose a priority." }
        if (assigneeId.isNotBlank() && assigneeId != requirement?.assigneeProfileId) {
            requireNotNull(ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(assigneeId)).profiles.profile) {
                "The selected assignee is unavailable."
            }
        }
        ctx.gql.execute(WorkOpsUpdateRequirement, WorkOpsUpdateRequirement.Variables(id,
            UpdateWorkOpsRequirementInput(priorityId = priorityId, assigneeProfileId = assigneeId.takeIf(String::isNotBlank),
                clearAssignee = assigneeId.isBlank(), expectedVersion = editVersion)))
        editing = false
        reload(ctx)
        feedback.message = "Requirement saved."
    }

    fun beginRename() { nameDraft = document?.data?.content?.metadata?.name.orEmpty(); renaming = true }
    fun cancelRename() { renaming = false }
    suspend fun rename(name: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name
        require(renaming && document?.canEdit == true) { "You do not have permission to rename this document." }
        require(name.isNotBlank()) { "Enter a requirement name." }
        ctx.gql.execute(WorkOpsRenameRequirement, WorkOpsRenameRequirement.Variables(requireNotNull(requirement).metadataId,
            MetadataInput(name = name.trim(), contentType = "bosca/v-document", languageTag = "en")))
        renaming = false
        reload(ctx)
        feedback.message = "Requirement renamed."
    }

    suspend fun beginTransition(transitionId: String, ctx: RenderContext) = feedback.perform {
        val task = requireNotNull(requirement?.task) { "The lifecycle task is unavailable." }
        val transition = requireNotNull(task.transitions.firstOrNull { it.id == transitionId }) { "Reload the available transitions." }
        transitionVersion = task.version
        if (transition.toState.status.category in listOf(WorkOpsStatusCategory.DONE, WorkOpsStatusCategory.CANCELLED)) pendingTransition = transitionId
        else transition(transitionId, null, ctx)
    }
    fun cancelTransition() { pendingTransition = "" }
    suspend fun completeTransition(resolutionId: String, ctx: RenderContext) = feedback.perform {
        require(pendingTransition.isNotBlank()) { "Choose a transition first." }
        require(data?.workOps?.tasks?.resolutions.orEmpty().any { it.id == resolutionId }) { "Choose a resolution." }
        transition(pendingTransition, resolutionId, ctx)
        pendingTransition = ""
    }
    private suspend fun transition(transitionId: String, resolutionId: String?, ctx: RenderContext) {
        ctx.gql.execute(WorkOpsTransitionTask, WorkOpsTransitionTask.Variables(requireNotNull(requirement?.task).id,
            transitionId, transitionVersion, resolutionId))
        reload(ctx)
        feedback.message = "Requirement status updated."
    }

    suspend fun comment(content: String, ctx: RenderContext) = feedback.perform {
        commentDraft = content
        require(content.isNotBlank()) { "Enter a comment." }
        ctx.gql.execute(WorkOpsCommentOnRequirement, WorkOpsCommentOnRequirement.Variables(id,
            WorkOpsRequirementCommentInput(content = content.trim(), visibility = ProfileVisibility.USER)))
        commentDraft = ""; commentRevision++
        reload(ctx)
        feedback.message = "Comment added."
    }
}

suspend fun workOpsRequirementDetail(): WorkOpsRequirementDetailModel {
    val ctx = currentRenderContext()
    val raw = ctx.params["id"].orEmpty()
    return WorkOpsRequirementDetailModel(raw, ctx.query["tab"].takeIf { it in setOf("comments", "history") } ?: "comments",
        ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { model ->
        model.feedback.perform {
            if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(raw)) {
                val resolved = ctx.gql.execute(WorkOpsRequirementIdByKey, WorkOpsRequirementIdByKey.Variables(raw.uppercase()))
                    .workOps.requirements.requirementByKey
                if (resolved == null) { ctx.notFound = true; return@perform }
                model.id = resolved.id
            }
            model.reload(ctx)
        }
    }
}
