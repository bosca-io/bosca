package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import java.net.URI

/** Authored specification contexts resolve their references through the owning, authorized APIs. */
@Serializable
class WorkOpsSpecContextsModel(
    val specId: String,
    val returnTo: String,
    var data: WorkOpsSpecContextsData? = null,
    var rows: List<WorkOpsSpecContextRow> = emptyList(),
    var adding: Boolean = false,
    var kind: String = "EXTERNAL_URI",
    var targetDraft: String = "",
    var targetName: String = "",
    var labelDraft: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    var revision: Long = 0,
) {
    val kinds get() = WorkOpsSpecContextType.entries.map { it.name }
    val searchable get() = kind in setOf("METADATA", "COLLECTION", "PROFILE", "SPEC", "TASK", "PROJECT")
    fun kindLabel(kind: String) = when (kind) {
        "GIT_RESOURCE" -> "Git resource"
        "METADATA" -> "Content item"
        "COLLECTION" -> "Collection"
        "PROFILE" -> "Person"
        "CHAT_CHANNEL" -> "Chat channel"
        "AI_SESSION" -> "AI session"
        "SPEC" -> "Specification"
        "TASK" -> "Task"
        "PROJECT" -> "Project"
        else -> "External link"
    }
    suspend fun reload(ctx: RenderContext) {
        data = ctx.gql.execute(WorkOpsSpecContexts, WorkOpsSpecContexts.Variables(specId))
        val spec = requireNotNull(data?.workOps?.specs?.spec) { "The specification is unavailable." }
        rows = spec.contexts.map { context ->
            val (name, href) = reference(context.contextType.name, context.targetId, ctx)
            WorkOpsSpecContextRow(context.id, kindLabel(context.contextType.name), context.label?.takeIf(String::isNotBlank)
                ?: name ?: "Reference unavailable", href, context.createdAt)
        }
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() { adding = true }
    fun cancel() { adding = false }
    fun chooseType(type: String, target: String, label: String) {
        labelDraft = label
        if (type != kind) { targetDraft = ""; targetName = "" } else targetDraft = target
        require(WorkOpsSpecContextType.entries.any { it.name == type }) { "Choose a context type." }
        kind = type
    }
    suspend fun add(target: String, label: String, ctx: RenderContext) = feedback.perform {
        targetDraft = target; labelDraft = label
        require(adding && target.isNotBlank()) { "Choose or enter a reference." }
        val type = WorkOpsSpecContextType.valueOf(kind)
        if (searchable) {
            targetName = requireNotNull(reference(kind, target.trim(), ctx).first) { "The reference is unavailable. Choose another reference." }
        } else if (type == WorkOpsSpecContextType.EXTERNAL_URI) {
            require(externalHref(target.trim()).isNotBlank()) { "Enter a full HTTP or HTTPS link." }
        }
        ctx.gql.execute(WorkOpsAddSpecContext, WorkOpsAddSpecContext.Variables(specId,
            CreateWorkOpsSpecContextInput(type, target.trim(), label.trim().takeIf(String::isNotBlank))))
        adding = false; targetDraft = ""; targetName = ""; labelDraft = ""; revision++
        reload(ctx)
        feedback.message = "Context added."
    }
    suspend fun remove(id: String, ctx: RenderContext) = feedback.perform {
        require(rows.any { it.id == id }) { "Reload the context before removing it." }
        check(ctx.gql.execute(WorkOpsRemoveSpecContext, WorkOpsRemoveSpecContext.Variables(specId, id)).workOps.specs.removeContext) {
            "The context was not removed."
        }
        reload(ctx)
        feedback.message = "Context removed."
    }

    private fun externalHref(target: String): String {
        val uri = try { URI(target) } catch (_: java.net.URISyntaxException) { return "" }
        return target.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() }.orEmpty()
    }
    private suspend fun reference(kind: String, target: String, ctx: RenderContext): Pair<String?, String> {
        if (kind == "EXTERNAL_URI") return target to externalHref(target)
        if (kind !in setOf("METADATA", "COLLECTION", "PROFILE", "SPEC", "TASK", "PROJECT")) return target to ""
        if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(target)) return null to ""
        return when (kind) {
            "METADATA" -> ctx.gql.execute(WorkOpsRequirementName, WorkOpsRequirementName.Variables(target)).content.metadata?.name
                ?.let { workOpsContentLabel(it, target, "Content") } to workOpsReferenceHref("metadata", target, returnTo)
            "COLLECTION" -> ctx.gql.execute(WorkOpsContextCollection, WorkOpsContextCollection.Variables(target)).content.collections.collection?.name to
                workOpsReferenceHref("collection", target, returnTo)
            "PROFILE" -> ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(target)).profiles.profile?.name to
                workOpsReferenceHref("profile", target, returnTo)
            "SPEC" -> ctx.gql.execute(WorkOpsSpecChoice, WorkOpsSpecChoice.Variables(target)).workOps.specs.spec?.let {
                it.metadata?.name?.let { name -> workOpsContentLabel(name, it.metadataId, "Specification") }
            } to "/workops/specs/${workOpsEncode(target)}"
            "TASK" -> ctx.gql.execute(WorkOpsRequirementParentTask, WorkOpsRequirementParentTask.Variables(target)).workOps.tasks.task?.summary
                ?.let { workOpsContentLabel(it, target, "Task") } to "/workops/tasks/${workOpsEncode(target)}"
            else -> ctx.gql.execute(WorkOpsContextProject, WorkOpsContextProject.Variables(target)).workOps.projects.project?.name to
                "/workops/projects/${workOpsEncode(target)}"
        }
    }
}

suspend fun workOpsSpecContexts(specId: String, returnTo: String): WorkOpsSpecContextsModel {
    val ctx = currentRenderContext()
    return WorkOpsSpecContextsModel(specId, returnTo).also { model -> model.feedback.perform { model.reload(ctx) } }
}
