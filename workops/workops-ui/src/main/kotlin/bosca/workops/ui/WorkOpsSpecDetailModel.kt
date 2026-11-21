package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Specification lifecycle and hierarchy use WorkOps; its independently authorized rich body uses Content. */
@Serializable
data class WorkOpsSpecDetailModel(
    var id: String,
    val tab: String = "comments",
    val offset: Long = 0,
    val childrenOffset: Long = 0,
    val requirementsOffset: Long = 0,
    var data: WorkOpsSpecDetailData? = null,
    var document: WorkOpsDocumentModel? = null,
    var parentName: String = "",
    var profileNames: Map<String, String> = emptyMap(),
    var editingOwner: Boolean = false,
    var ownerDraft: String = "",
    var editVersion: Long = 0,
    var renaming: Boolean = false,
    var nameDraft: String = "",
    var pendingTransition: String = "",
    var transitionVersion: Long = 0,
    var commentDraft: String = "",
    var commentRevision: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val generationsOffset: Long = 0,
) {
    val spec get() = data?.workOps?.specs?.spec
    val href get() = "/workops/specs/${workOpsEncode(id)}"
    val title get() = document?.data?.content?.metadata?.let { workOpsContentLabel(it.name, spec?.metadataId.orEmpty(), "Specification") }
        ?: if (spec != null) "Specification" else if (feedback.failed) "Could not load specification" else "Specification unavailable"
    val ownerLabel get() = workOpsAssigneeLabel(spec?.ownerProfileId, spec?.owner?.name)
    val statusTone get() = if (spec?.status?.category == WorkOpsStatusCategory.DONE) "success" else "neutral"
    val parentHref get() = spec?.parentSpecId?.let { "/workops/specs/${workOpsEncode(it)}" }.orEmpty()
    val children get() = data?.workOps?.specs?.children.orEmpty().take(25)
    val requirementsHref get() = "$href?tab=$tab&offset=$offset&childrenOffset=$childrenOffset&generationsOffset=$generationsOffset"
    val generationsHref get() = "$href?tab=$tab&offset=$offset&childrenOffset=$childrenOffset&requirementsOffset=$requirementsOffset"
    val tabs get() = listOf("comments" to "Comments", "history" to "History")
        .map { (value, label) -> NavigationItem(label, pageHref(tab = value, offset = 0), value == tab) }
    val comments get() = spec?.comments.orEmpty()
    val hasNext get() = if (tab == "history") spec?.history.orEmpty().size > 25 else comments.size > 25
    val previousHref get() = if (offset > 0) pageHref(offset = (offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (hasNext) pageHref(offset = offset + 25) else ""
    val previousChildrenHref get() = if (childrenOffset > 0) pageHref(childrenOffset = (childrenOffset - 25).coerceAtLeast(0)) else ""
    val nextChildrenHref get() = if (data?.workOps?.specs?.children.orEmpty().size > 25) pageHref(childrenOffset = childrenOffset + 25) else ""
    private fun pageHref(tab: String = this.tab, offset: Long = this.offset, childrenOffset: Long = this.childrenOffset) =
        "$href?tab=$tab&offset=$offset&childrenOffset=$childrenOffset&requirementsOffset=$requirementsOffset&generationsOffset=$generationsOffset"

    fun profileName(id: String?) = if (id == null) "System" else profileNames[id] ?: "Profile unavailable"
    fun historyField(key: String) = when (key) {
        "created" -> "Specification"
        "owner_profile_id" -> "Owner"
        "parent_spec_id" -> "Parent specification"
        "metadata_id" -> "Document"
        "status_id" -> "Status"
        "project_id" -> "Project"
        "context" -> "Context"
        "task_generation" -> "Tasks"
        else -> key.replace('_', ' ')
    }
    fun historyValue(key: String, value: JsonElement?): String {
        if (value == null || value == JsonNull) return "Unset"
        val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        return when (key) {
            "created" -> "Created"
            "owner_profile_id" -> profileName(text)
            "status_id" -> data?.workOps?.statuses?.all.orEmpty().firstOrNull { it.id == text }?.name ?: "Status unavailable"
            "metadata_id" -> if (text == spec?.metadataId) title else "Previous document"
            "parent_spec_id" -> if (text == spec?.parentSpecId) parentName.ifBlank { "Specification unavailable" } else "Previous parent"
            "project_id" -> if (text == spec?.project?.id) spec?.project?.name.orEmpty() else "Previous project"
            "context" -> when { text.startsWith("added:") -> "Added"; text.startsWith("removed:") -> "Removed"; else -> "Changed" }
            "task_generation" -> "Generated tasks"
            else -> if (key.endsWith("_id") || key.endsWith("_ids")) "Changed reference" else text
        }
    }

    suspend fun reload(ctx: RenderContext) {
        data = ctx.gql.execute(WorkOpsSpecDetail, WorkOpsSpecDetail.Variables(id, offset, childrenOffset))
        val spec = spec ?: run { document = null; ctx.notFound = true; return }
        document = (document?.takeIf { it.metadataId == spec.metadataId } ?: WorkOpsDocumentModel(spec.metadataId)).also { it.reload(ctx) }
        parentName = spec.parentSpecId?.let { parent ->
            ctx.gql.execute(WorkOpsSpecChoice, WorkOpsSpecChoice.Variables(parent)).workOps.specs.spec?.let {
                workOpsContentLabel(it.metadata?.name.orEmpty(), it.metadataId, "Specification unavailable")
            } ?: "Specification unavailable"
        }.orEmpty()
        val names = mutableMapOf<String, String>()
        data?.profiles?.current.orEmpty().forEach { names[it.id] = it.name }
        spec.owner?.let { names[it.id] = it.name }
        comments.mapNotNull { it.profile }.forEach { names[it.id] = it.name }
        val previousOwners = spec.history.flatMap { entry -> entry.changes.filter { it.fieldKey == "owner_profile_id" }
            .flatMap { listOfNotNull((it.fromValue as? JsonPrimitive)?.contentOrNull, (it.toValue as? JsonPrimitive)?.contentOrNull) } }
        for (profileId in (spec.history.mapNotNull { it.changedByProfileId } + previousOwners).distinct().filterNot(names::containsKey)) {
            ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(profileId)).profiles.profile?.let { names[it.id] = it.name }
        }
        profileNames = names
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun editOwner() {
        spec?.let { ownerDraft = it.ownerProfileId; editVersion = it.version; editingOwner = true }
    }
    fun cancelOwner() { editingOwner = false }
    suspend fun saveOwner(ownerId: String, ctx: RenderContext) = feedback.perform {
        ownerDraft = ownerId
        require(editingOwner && spec != null) { "Open the owner editor before saving." }
        require(ownerId.isNotBlank()) { "Choose an owner." }
        if (ownerId != spec?.ownerProfileId) requireNotNull(ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(ownerId)).profiles.profile) {
            "The selected owner is unavailable."
        }
        ctx.gql.execute(WorkOpsUpdateSpec, WorkOpsUpdateSpec.Variables(id, UpdateWorkOpsSpecInput(ownerProfileId = ownerId, expectedVersion = editVersion)))
        editingOwner = false
        reload(ctx)
        feedback.message = "Owner updated."
    }
    fun beginRename() { nameDraft = document?.data?.content?.metadata?.name.orEmpty(); renaming = true }
    fun cancelRename() { renaming = false }
    suspend fun rename(name: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name
        require(renaming && document?.canEdit == true) { "You do not have permission to rename this document." }
        require(name.isNotBlank()) { "Enter a specification name." }
        ctx.gql.execute(WorkOpsRenameSpec, WorkOpsRenameSpec.Variables(requireNotNull(spec).metadataId,
            MetadataInput(name = name.trim(), contentType = "bosca/v-document", languageTag = "en")))
        renaming = false
        reload(ctx)
        feedback.message = "Specification renamed."
    }
    suspend fun beginTransition(transitionId: String, ctx: RenderContext) = feedback.perform {
        val spec = requireNotNull(spec) { "Reload the specification first." }
        val transition = requireNotNull(spec.transitions.firstOrNull { it.id == transitionId }) { "Reload the available transitions." }
        transitionVersion = spec.version
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
        ctx.gql.execute(WorkOpsTransitionSpec, WorkOpsTransitionSpec.Variables(id, transitionId, transitionVersion, resolutionId))
        reload(ctx)
        feedback.message = "Specification status updated."
    }
    suspend fun comment(content: String, ctx: RenderContext) = feedback.perform {
        commentDraft = content
        require(content.isNotBlank()) { "Enter a comment." }
        ctx.gql.execute(WorkOpsCommentOnSpec, WorkOpsCommentOnSpec.Variables(id,
            WorkOpsSpecCommentInput(content = content.trim(), visibility = ProfileVisibility.USER)))
        commentDraft = ""; commentRevision++
        reload(ctx)
        feedback.message = "Comment added."
    }
}

suspend fun workOpsSpecDetail(): WorkOpsSpecDetailModel {
    val ctx = currentRenderContext()
    val raw = ctx.params["id"].orEmpty()
    return WorkOpsSpecDetailModel(raw, ctx.query["tab"].takeIf { it in setOf("comments", "history") } ?: "comments",
        ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["childrenOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["requirementsOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        generationsOffset = ctx.query["generationsOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { model ->
        model.feedback.perform {
            if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(raw)) {
                val resolved = ctx.gql.execute(WorkOpsSpecIdByKey, WorkOpsSpecIdByKey.Variables(raw.uppercase())).workOps.specs.specByKey
                if (resolved == null) { ctx.notFound = true; return@perform }
                model.id = resolved.id
            }
            model.reload(ctx)
        }
    }
}
