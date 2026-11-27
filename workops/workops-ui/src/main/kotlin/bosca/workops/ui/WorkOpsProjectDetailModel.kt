package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Project facts and moves stay independent of the selected board and catalog islands. */
@Serializable
class WorkOpsProjectDetailModel(
    val id: String,
    val tab: String = "overview",
    var selectedBoardId: String = "",
    var project: WorkOpsProjectRecord? = null,
    var programs: List<WorkOpsPlanningRow> = emptyList(),
    var boards: List<WorkOpsBoardChoice> = emptyList(),
    var catalogs: WorkOpsProjectCatalogsData? = null,
    var recent: WorkOpsProjectRecentTasksData? = null,
    var sprintCount: Int = 0,
    var activeSprintCount: Int = 0,
    var overviewLoaded: Boolean = false,
    var moveTarget: WorkOpsProjectRecord? = null,
    var moveProgramId: String = "",
    var moved: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val href get() = "/workops/projects/${workOpsEncode(id)}"
    val title get() = project?.name ?: if (feedback.failed) "Could not load project" else "Project unavailable"
    val programName get() = programs.firstOrNull { it.id == project?.programId }?.name ?: "Program unavailable"
    val selectedBoard get() = boards.firstOrNull { it.id == selectedBoardId }
    val tabs get() = listOf("overview", "board", "versions", "components", "dependencies", "labels", "repositories", "analytics")
        .map { value -> NavigationItem(value.replaceFirstChar(Char::uppercase), "$href?tab=$value" +
            selectedBoardId.takeIf(String::isNotBlank)?.let { "&boardId=${workOpsEncode(it)}" }.orEmpty(), tab == value) }
    val recentRows get() = recent?.workOps?.savedFilters?.searchTasks?.rows.orEmpty()
    val versions get() = catalogs?.workOps?.versions?.byProject.orEmpty()
    val components get() = catalogs?.workOps?.components?.byProject.orEmpty()
    val labels get() = catalogs?.workOps?.labels?.byProject.orEmpty()
    val moveChoices get() = programs.filter { !it.archived && it.id != moveTarget?.programId }
    val statusCounts get() = WorkOpsStatusCategory.entries.map { category ->
        WorkOpsProjectStatusCount(when (category) {
            WorkOpsStatusCategory.TODO -> "To do"
            WorkOpsStatusCategory.IN_PROGRESS -> "In progress"
            WorkOpsStatusCategory.DONE -> "Done"
            WorkOpsStatusCategory.CANCELLED -> "Cancelled"
        }, recentRows.count { it.status.category == category }, recentRows.size)
    }

    suspend fun reload(ctx: RenderContext) {
        overviewLoaded = false
        val data = ctx.gql.execute(WorkOpsProjectDetailInfo, WorkOpsProjectDetailInfo.Variables(id)).workOps
        project = data.projects.project?.projectRecord()
        if (project == null) { ctx.notFound = true; return }
        programs = data.programs.all.map { WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name, archived = it.archivedAt != null) }
        if (tab in setOf("overview", "board")) {
            val directory = WorkOpsBoardDirectoryModel(id, selectedBoardId)
            directory.reload(ctx)
            boards = directory.boards
            if (selectedBoardId.isBlank() && !ctx.query.containsKey("boardId")) selectedBoardId = boards.firstOrNull()?.id.orEmpty()
        }
        if (tab == "overview") {
            catalogs = ctx.gql.execute(WorkOpsProjectCatalogs, WorkOpsProjectCatalogs.Variables(id))
            val source = "project = ${Json.encodeToString(String.serializer(), id)} ORDER BY modified DESC"
            recent = ctx.gql.execute(WorkOpsProjectRecentTasks, WorkOpsProjectRecentTasks.Variables(source))
            var count = 0; var active = 0
            for (board in boards) {
                var offset = 0L
                do {
                    val page = ctx.gql.execute(WorkOpsSprintsForBoard, WorkOpsSprintsForBoard.Variables(board.id, offset)).workOps.sprints.byBoard
                    count += page.size; active += page.count { it.state == WorkOpsSprintState.ACTIVE }; offset += page.size
                } while (page.size == 100)
            }
            sprintCount = count; activeSprintCount = active; overviewLoaded = true
        }
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun beginMove() {
        val inspected = requireNotNull(project) { "Reload this project first." }
        require(!inspected.archived) { "Unarchive the project before moving it." }
        moveTarget = inspected; moveProgramId = ""; moved = false; feedback.message = ""; feedback.failed = false
    }
    fun cancelMove() { moveTarget = null }
    suspend fun reloadMoveChoices(program: String, ctx: RenderContext) = feedback.perform {
        moveProgramId = program
        programs = ctx.gql.execute(WorkOpsPlanningProgramChoices, Unit).workOps.programs.all.map {
            WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name, archived = it.archivedAt != null)
        }
    }
    suspend fun move(program: String, ctx: RenderContext) = feedback.perform {
        moveProgramId = program
        val target = requireNotNull(moveTarget) { "Open the project move dialog first." }
        require(moveChoices.any { it.id == program }) { "Choose another available active program." }
        ctx.gql.execute(WorkOpsMoveProject, WorkOpsMoveProject.Variables(target.id, program, target.version))
        moveTarget = null; moved = true
        reload(ctx); feedback.message = "Project moved."
    }
}

suspend fun workOpsProjectDetail(): WorkOpsProjectDetailModel {
    val ctx = currentRenderContext()
    val tabs = setOf("overview", "board", "versions", "components", "dependencies", "labels", "repositories", "analytics")
    return WorkOpsProjectDetailModel(ctx.params["id"].orEmpty(), ctx.query["tab"].takeIf { it in tabs } ?: "overview",
        ctx.query["boardId"].orEmpty()).also { it.retry(ctx) }
}
