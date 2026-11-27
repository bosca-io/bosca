package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Generation uses the existing WorkOps operation and records the current Content version when visible. */
@Serializable
class WorkOpsSpecGenerationsModel(
    val specId: String,
    val returnTo: String,
    val offset: Long = 0,
    var data: WorkOpsSpecGenerationsData? = null,
    var taskNames: Map<String, String> = emptyMap(),
    var versionDraft: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val spec get() = data?.workOps?.specs?.spec
    val rows get() = spec?.taskGenerations.orEmpty().take(25)
    val documentVersion get() = spec?.metadata?.version
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (spec?.taskGenerations.orEmpty().size > 25) href(offset + 25) else ""
    private fun href(offset: Long) = returnTo + (if ('?' in returnTo) "&" else "?") + "generationsOffset=$offset"
    fun taskName(id: String) = taskNames[id] ?: "Task unavailable"
    fun taskCountLabel(count: Int) = "$count ${if (count == 1) "task" else "tasks"}"
    fun sourceLabel(source: WorkOpsGenerationSource) = when (source) {
        WorkOpsGenerationSource.MANUAL -> "Manual"
        WorkOpsGenerationSource.KIT -> "KIT"
        WorkOpsGenerationSource.CLAUDE_CODE -> "Claude Code"
    }
    suspend fun reload(ctx: RenderContext) {
        data = ctx.gql.execute(WorkOpsSpecGenerations, WorkOpsSpecGenerations.Variables(specId, offset))
        requireNotNull(spec) { "The specification is unavailable." }
        if (versionDraft.isBlank()) versionDraft = documentVersion?.toString().orEmpty()
        val names = mutableMapOf<String, String>()
        for (id in rows.flatMap { it.generatedTaskIds }.distinct()) {
            ctx.gql.execute(WorkOpsRequirementParentTask, WorkOpsRequirementParentTask.Variables(id)).workOps.tasks.task?.let {
                names[id] = workOpsContentLabel(it.summary, id, "Task")
            }
        }
        taskNames = names
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun generate(version: String, ctx: RenderContext) = feedback.perform {
        versionDraft = version
        val fresh = requireNotNull(ctx.gql.execute(WorkOpsSpecGenerationVersion, WorkOpsSpecGenerationVersion.Variables(specId)).workOps.specs.spec) {
            "The specification is unavailable."
        }
        val currentVersion = fresh.metadata?.version ?: version.toIntOrNull()?.takeIf { it > 0 }
        require(currentVersion != null) { "Enter the document version to record with this generation." }
        versionDraft = currentVersion.toString()
        val generated = ctx.gql.execute(WorkOpsGenerateSpecTasks, WorkOpsGenerateSpecTasks.Variables(specId, currentVersion))
            .workOps.specs.generateTasks
        reload(ctx)
        feedback.message = "Generated ${taskCountLabel(generated.generatedTaskIds.size)}."
    }
}

suspend fun workOpsSpecGenerations(specId: String, returnTo: String): WorkOpsSpecGenerationsModel {
    val ctx = currentRenderContext()
    return WorkOpsSpecGenerationsModel(specId, returnTo, ctx.query["generationsOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
        .also { model -> model.feedback.perform { model.reload(ctx) } }
}
