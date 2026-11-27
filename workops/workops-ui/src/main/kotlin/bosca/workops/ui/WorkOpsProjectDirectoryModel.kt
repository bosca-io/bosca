package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.WorkOpsVersionProjects
import kotlinx.serialization.Serializable

/** Authorized project selection for WorkOps catalogs, including archived projects. */
@Serializable
class WorkOpsProjectDirectoryModel(
    var projectId: String = "",
    var projects: List<WorkOpsPlanningRow> = emptyList(),
    var loaded: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val selected get() = projects.firstOrNull { it.id == projectId }

    suspend fun reload(ctx: RenderContext) = feedback.perform {
        loaded = false
        projects = ctx.gql.execute(WorkOpsVersionProjects, Unit).workOps.projects.all.map {
            WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name)
        }
        if (projectId.isBlank()) projectId = projects.firstOrNull()?.id.orEmpty()
        loaded = true
    }
}

suspend fun workOpsProjectDirectory(): WorkOpsProjectDirectoryModel {
    val ctx = currentRenderContext()
    return WorkOpsProjectDirectoryModel(ctx.query["projectId"].orEmpty()).also { it.reload(ctx) }
}
