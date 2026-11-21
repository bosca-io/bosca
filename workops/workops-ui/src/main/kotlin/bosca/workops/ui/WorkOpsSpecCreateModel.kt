package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Creates the specification aggregate with an authored name, retaining rejected drafts and lookup failures. */
@Serializable
class WorkOpsSpecCreateModel(
    var creating: Boolean = false,
    var projectId: String = "",
    var parentId: String = "",
    var parentName: String = "",
    var nameDraft: String = "",
    var data: WorkOpsSpecCreateChoicesData? = null,
    var createdId: String = "",
    val refreshHref: String = "/workops/specs",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val projects get() = data?.workOps?.projects?.all.orEmpty().filter { it.archivedAt == null }
    suspend fun open(ctx: RenderContext) = feedback.perform {
        creating = true; createdId = ""
        val firstLoad = data == null
        data = ctx.gql.execute(WorkOpsSpecCreateChoices, Unit)
        if (firstLoad && projectId.isBlank()) projectId = projects.firstOrNull()?.id.orEmpty()
        if (parentId.isNotBlank()) resolveParent(ctx)
        require(projects.isNotEmpty()) { "No active project is available for creating a specification." }
        require(projectId.isBlank() || projects.any { it.id == projectId }) { "The selected project is unavailable. Choose another project." }
    }
    fun cancel() { creating = false }

    suspend fun reloadChoices(name: String, project: String, parent: String, ctx: RenderContext) {
        capture(name, project, parent)
        open(ctx)
    }

    private fun capture(name: String, project: String, parent: String) {
        nameDraft = name; projectId = project
        if (parentId != parent) parentName = ""
        parentId = parent
    }

    private suspend fun resolveParent(ctx: RenderContext) {
        val parent = requireNotNull(ctx.gql.execute(WorkOpsSpecChoice, WorkOpsSpecChoice.Variables(parentId)).workOps.specs.spec) {
            "The parent specification is unavailable. Choose another parent."
        }
        parentName = workOpsContentLabel(parent.metadata?.name.orEmpty(), parent.metadataId, "Specification unavailable")
    }

    suspend fun create(name: String, project: String, parent: String, ctx: RenderContext) = feedback.perform {
        capture(name, project, parent)
        require(creating && name.isNotBlank()) { "Enter a specification name." }
        require(projects.any { it.id == project }) { "Choose an available active project." }
        if (parent.isNotBlank()) resolveParent(ctx)
        createdId = ctx.gql.execute(WorkOpsCreateSpec, WorkOpsCreateSpec.Variables(CreateWorkOpsSpecInput(
            name = name.trim(), projectId = project, parentSpecId = parent.takeIf(String::isNotBlank),
        ))).workOps.specs.create.id
        creating = false; nameDraft = ""
        feedback.message = "Specification created."
    }
}

suspend fun workOpsSpecCreate(): WorkOpsSpecCreateModel {
    val ctx = currentRenderContext()
    return WorkOpsSpecCreateModel(projectId = ctx.query["project"].orEmpty(), parentId = ctx.query["parent"].orEmpty(),
        refreshHref = "/workops/specs?bql=${workOpsEncode(ctx.query["bql"] ?: "ORDER BY modified DESC")}&offset=${ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0}")
        .also { if (ctx.query["new"] == "1") it.open(ctx) }
}
