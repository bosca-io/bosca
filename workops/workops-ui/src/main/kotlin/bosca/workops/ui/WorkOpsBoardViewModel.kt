package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.design.model.BoardMoveTarget
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** WorkOps supplies scope, transitions, resolutions and writes to neutral shared board controls. */
@Serializable
class WorkOpsBoardViewModel(
    val id: String,
    var data: WorkOpsBoardViewData? = null,
    var cards: List<WorkOpsBoardCard> = emptyList(),
    var sprints: List<WorkOpsBoardSprint> = emptyList(),
    var sprintId: String = "active",
    var truncated: Boolean = false,
    var pendingMove: WorkOpsBoardMove? = null,
    var resolutionId: String = "",
    var moveSequence: Long = 0,
    var moveResult: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val instance: String = "board",
    var adding: Boolean = false,
    var summaryDraft: String = "",
    var projectDraft: String = "",
    var createdId: String = "",
    var cardsLoaded: Boolean = false,
) {
    val board get() = data?.workOps?.boards?.board
    val title get() = board?.name ?: "Board unavailable"
    val columns get() = board?.columns.orEmpty().sortedBy { it.displayOrder }
        .map { WorkOpsBoardColumn(it.id, it.name, it.displayOrder, it.statusIds, it.wipLimit) }
    val projects get() = data?.workOps?.projects?.all.orEmpty().filter { project ->
        val board = board ?: return@filter false
        project.id == board.projectId || board.projects.any { it.id == project.id } ||
            board.programId != null && board.programId == project.program.id ||
            board.portfolioId != null && board.portfolioId == project.program.portfolio.id
    }
    val projectIds get() = buildSet {
        board?.projectId?.let(::add)
        board?.projects.orEmpty().forEach { add(it.id) }
        projects.forEach { add(it.id) }
    }
    val resolutions get() = data?.workOps?.tasks?.resolutions.orEmpty()
    val creationProjects get() = projects.filter { it.archivedAt == null }
    val canQuickAdd get() = cardsLoaded && creationProjects.isNotEmpty() && (sprintId == "all" || selectedSprint?.state in setOf("FUTURE", "ACTIVE"))
    val createdHref get() = createdId.takeIf(String::isNotBlank)?.let { "/workops/tasks/${workOpsEncode(it)}" }.orEmpty()
    val selectedSprint get() = sprints.firstOrNull { it.id == sprintId }
    val source get() = "project IN (${projectIds.joinToString(", ") { quote(it) }}) ORDER BY priority ASC"
    val tasksHref get() = "/workops/tasks?bql=${workOpsEncode(source)}"
    val unmapped get() = cards.filter { card -> columns.none { card.statusId in it.statusIds } }
    private fun columnForStatus(statusId: String) = columns.firstOrNull { statusId in it.statusIds }
    fun cardsFor(column: WorkOpsBoardColumn) = cards.filter { columnForStatus(it.statusId)?.id == column.id }
    fun exceedsLimit(column: WorkOpsBoardColumn) = column.wipLimit?.let { cardsFor(column).size > it } ?: false
    fun countLabel(column: WorkOpsBoardColumn): String {
        val count = cardsFor(column).size
        return "$count ${if (count == 1) "task" else "tasks"}" + column.wipLimit?.let { " · WIP limit $it" }.orEmpty()
    }
    fun targets(card: WorkOpsBoardCard) = columns.filter { column ->
        columnForStatus(card.statusId)?.id != column.id && card.transitions.any { columnForStatus(it.statusId)?.id == column.id }
    }.map { BoardMoveTarget(it.id, it.name) }
    fun isClosing(card: WorkOpsBoardCard, columnId: String) = transition(card, columnId).closing
    private fun transition(card: WorkOpsBoardCard, columnId: String): WorkOpsBoardTransition {
        val column = requireNotNull(columns.firstOrNull { it.id == columnId }) { "Reload the available columns." }
        require(columnForStatus(card.statusId)?.id != column.id) { "This task is already in that column." }
        return requireNotNull(card.transitions.firstOrNull { columnForStatus(it.statusId)?.id == column.id }) { "This task cannot move to that column. Reload its available transitions." }
    }

    suspend fun reload(ctx: RenderContext) {
        cardsLoaded = false
        data = ctx.gql.execute(WorkOpsBoardView, WorkOpsBoardView.Variables(id))
        cards = emptyList(); truncated = false
        if (board == null) return
        val choices = mutableListOf<WorkOpsBoardSprint>()
        var offset = 0L
        do {
            val page = ctx.gql.execute(WorkOpsBoardViewSprints, WorkOpsBoardViewSprints.Variables(id, offset)).workOps.sprints.byBoard
            choices += page.map { WorkOpsBoardSprint(it.id, it.name, it.state.name, it.endDate, (it.committedTaskIds + it.addedDuringSprintTaskIds).distinct()) }
            offset += page.size
        } while (page.size == 100)
        sprints = choices
        if (sprintId == "active") sprintId = sprints.firstOrNull { it.state == "ACTIVE" }?.id ?: "all"
        loadCards(ctx)
    }
    private suspend fun loadCards(ctx: RenderContext) {
        cardsLoaded = false
        require(projects.isNotEmpty()) { "No projects in this board are available to you." }
        if (sprintId == "all") {
            val rows = ctx.gql.execute(WorkOpsBoardCards, WorkOpsBoardCards.Variables(source)).workOps.savedFilters.searchTasks.rows
            cards = rows.map { it.boardCard() }.filter { it.projectId in projectIds }
            truncated = rows.size == 200
        } else {
            val sprint = requireNotNull(selectedSprint) { "The selected sprint is unavailable. Choose another sprint." }
            val members = mutableListOf<WorkOpsBoardCard>()
            for (id in sprint.taskIds) {
                val task = ctx.gql.execute(WorkOpsBoardTask, WorkOpsBoardTask.Variables(id)).workOps.tasks.task
                task?.boardCard()?.takeIf { it.projectId in projectIds }?.let(members::add)
            }
            cards = members
            truncated = false
        }
        cardsLoaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun filterSprint(value: String, ctx: RenderContext) = feedback.perform {
        sprintId = value
        cards = emptyList()
        loadCards(ctx)
    }
    fun beginQuickAdd() {
        adding = true; summaryDraft = ""; createdId = ""
        projectDraft = creationProjects.firstOrNull()?.id.orEmpty()
        feedback.failed = false; feedback.message = ""
    }
    fun cancelQuickAdd() { adding = false }
    suspend fun quickAdd(summary: String, projectId: String, ctx: RenderContext) = feedback.perform {
        summaryDraft = summary; projectDraft = projectId
        require(adding && canQuickAdd) { "Choose an open board or sprint before adding a task." }
        require(summary.isNotBlank() && summary.trim().length <= 255) { "Enter a task summary of up to 255 characters." }
        require(creationProjects.any { it.id == projectId }) { "Choose an available project in this board." }
        val created = ctx.gql.execute(WorkOpsQuickAddBoardTask, WorkOpsQuickAddBoardTask.Variables(projectId, summary.trim(), selectedSprint?.id))
            .workOps.tasks.create.boardCard()
        createdId = created.id; adding = false
        reload(ctx)
        feedback.message = "Task created in its workflow's initial state."
    }

    /** Stages a terminal transition while retaining the task version the user inspected. */
    fun prepareMove(cardId: String, columnId: String, expectedVersion: Long) {
        require(pendingMove == null) { "Finish the current closing move first." }
        val card = requireNotNull(cards.firstOrNull { it.id == cardId }) { "Reload this task before moving it." }
        require(card.version == expectedVersion) { "This task changed. Reload it before moving." }
        val transition = transition(card, columnId)
        require(transition.closing) { "This transition does not need a closing confirmation." }
        pendingMove = WorkOpsBoardMove(card.id, columnId, card.label, transition.id, expectedVersion)
        resolutionId = ""; moveResult = ""; feedback.message = ""; feedback.failed = false
    }
    fun cancelMove() { if (pendingMove == null) return; pendingMove = null; moveResult = "canceled"; moveSequence++ }
    suspend fun completeMove(resolution: String, ctx: RenderContext) = feedback.perform {
        resolutionId = resolution
        val move = requireNotNull(pendingMove) { "Choose a closing move first." }
        require(resolutions.any { it.id == resolution }) { "Choose a resolution." }
        val updated = ctx.gql.execute(WorkOpsMoveBoardCard, WorkOpsMoveBoardCard.Variables(move.cardId, move.transitionId, move.version, resolution)).workOps.tasks.transition.boardCard()
        cards = cards.map { if (it.id == updated.id) updated else it }
        pendingMove = null; moveResult = "saved"; moveSequence++
    }

    /** Executes only an available nonterminal transition with the version rendered on the card. */
    suspend fun move(cardId: String, columnId: String, expectedVersion: Long, ctx: RenderContext) {
        val card = requireNotNull(cards.firstOrNull { it.id == cardId }) { "Reload this task before moving it." }
        require(card.version == expectedVersion) { "This task changed. Reload it before moving." }
        val transition = transition(card, columnId)
        require(!transition.closing) { "Choose a resolution before closing this task." }
        val updated = ctx.gql.execute(WorkOpsMoveBoardCard, WorkOpsMoveBoardCard.Variables(card.id, transition.id, expectedVersion, null)).workOps.tasks.transition.boardCard()
        cards = cards.map { if (it.id == updated.id) updated else it }
    }

    companion object {
        private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
