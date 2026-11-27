package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Project/board selection and creation stay independent from the selected board's live state. */
@Serializable
class WorkOpsBoardDirectoryModel(
    var projectId: String = "",
    var boardId: String = "",
    var projects: List<WorkOpsPlanningRow> = emptyList(),
    var boards: List<WorkOpsBoardChoice> = emptyList(),
    var creating: Boolean = false,
    var nameDraft: String = "",
    var typeDraft: String = "KANBAN",
    var scopeDraft: String = "single",
    var swimlaneDraft: String = "NONE",
    var projectDraft: List<String> = emptyList(),
    var createdId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val selectedBoard get() = boards.firstOrNull { it.id == boardId }
    val activeProjects get() = projects.filterNot { it.archived }
    val createdHref: String get() {
        if (createdId.isBlank()) return ""
        val scopeProject = if (scopeDraft == "multi" && projectId !in projectDraft) projectDraft.firstOrNull().orEmpty() else projectId
        return "/workops/boards?projectId=${workOpsEncode(scopeProject)}&boardId=${workOpsEncode(createdId)}"
    }
    fun label(board: WorkOpsBoardChoice): String {
        val projectKeys = projects.filter { it.id in board.projectIds }.joinToString(", ") { it.key }
        return board.name + if (board.projectId == null && board.programId == null && board.portfolioId == null && projectKeys.isNotBlank()) " ($projectKeys)" else ""
    }
    suspend fun reload(ctx: RenderContext, chooseFirst: Boolean = false) {
        projects = ctx.gql.execute(WorkOpsBoardProjects, Unit).workOps.projects.all.map {
            WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name, archived = it.archivedAt != null)
        }
        boards = emptyList()
        if (chooseFirst && !ctx.query.containsKey("projectId")) projectId = activeProjects.firstOrNull()?.id.orEmpty()
        if (projectId.isBlank()) return
        require(projects.any { it.id == projectId }) { "The selected project is unavailable." }
        val results = ctx.gql.execute(WorkOpsBoardsForProject, WorkOpsBoardsForProject.Variables(projectId)).workOps.boards
        boards = (results.byProject + results.byProjects).distinctBy { it.id }.map {
            WorkOpsBoardChoice(it.id, it.name, it.type.name, it.projectId, it.programId, it.portfolioId, it.projects.map { project -> project.id })
        }
        if (chooseFirst && !ctx.query.containsKey("boardId")) boardId = boards.firstOrNull()?.id.orEmpty()
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() {
        creating = true; nameDraft = ""; typeDraft = "KANBAN"; scopeDraft = "single"; swimlaneDraft = "NONE"
        projectDraft = listOfNotNull(projectId.takeIf { id -> activeProjects.any { it.id == id } })
        createdId = ""; feedback.failed = false; feedback.message = ""
    }
    fun cancel() { creating = false }
    suspend fun create(name: String, type: String, scope: String, swimlane: String, projectsJson: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name; typeDraft = type; scopeDraft = scope; swimlaneDraft = swimlane
        projectDraft = if (projectsJson.isBlank()) emptyList() else Json.decodeFromString(ListSerializer(String.serializer()), projectsJson).distinct()
        require(creating) { "Open the new board form first." }
        require(name.isNotBlank()) { "Enter a board name." }
        require(type in setOf("KANBAN", "SCRUM") && scope in setOf("single", "multi")) { "Choose a board type and scope." }
        require(swimlane in setOf("NONE", "ASSIGNEE", "EPIC", "PRIORITY") || swimlane == "PROJECT" && scope == "multi") { "Project swimlanes require a multi-project board." }
        if (scope == "single") require(activeProjects.any { it.id == projectId }) { "Select an active project first." }
        else require(projectDraft.size >= 2 && projectDraft.all { id -> activeProjects.any { it.id == id } }) { "Choose at least two available active projects." }
        createdId = ctx.gql.execute(WorkOpsCreateBoard, WorkOpsCreateBoard.Variables(CreateWorkOpsBoardInput(
            projectId = projectId.takeIf { scope == "single" }, projectIds = projectDraft.takeIf { scope == "multi" },
            name = name.trim(), type = WorkOpsBoardType.valueOf(type), swimlaneStrategy = WorkOpsSwimlaneStrategy.valueOf(swimlane),
        ))).workOps.boards.create.id
        creating = false
        reload(ctx)
        feedback.message = "Board created. Open it to configure columns and view tasks."
    }
}

suspend fun workOpsBoardDirectory(): WorkOpsBoardDirectoryModel {
    val ctx = currentRenderContext()
    return WorkOpsBoardDirectoryModel(ctx.query["projectId"].orEmpty(), ctx.query["boardId"].orEmpty()).also { model ->
        model.feedback.perform { model.reload(ctx, chooseFirst = true) }
    }
}

suspend fun workOpsSelectedBoard(id: String): WorkOpsBoardViewModel? = id.takeIf(String::isNotBlank)?.let {
    WorkOpsBoardViewModel(it).also { model -> model.retry(currentRenderContext()) }
}
