package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** WorkOps owns board settings, column mappings and the API's sequential column-order swap. */
@Serializable
class WorkOpsBoardConfigurationModel(
    val id: String,
    val tab: String = "board",
    var data: WorkOpsBoardConfigurationData? = null,
    var statusData: WorkOpsBoardColumnStatusesData? = null,
    var settings: WorkOpsBoardSettingsDraft? = null,
    var columnDraft: WorkOpsBoardColumnDraft? = null,
    var deleteTarget: WorkOpsBoardColumn? = null,
    var columnsLoaded: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val board get() = data?.workOps?.boards?.board
    val title get() = board?.name ?: "Board unavailable"
    val subtitle: String get() {
        val board = board ?: return ""
        val keys = projects.filter { project -> project.id == board.projectId || board.projects.any { it.id == project.id } }.joinToString(", ") { it.key }
        return board.type.name.lowercase() + keys.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
    }
    val href get() = "/workops/boards/${workOpsEncode(id)}"
    val tabs get() = listOf("board" to "Board", "sprints" to "Sprints", "columns" to "Columns", "settings" to "Settings")
        .map { (value, label) -> NavigationItem(label, "$href?tab=$value", tab == value) }
    val columns get() = board?.columns.orEmpty().sortedBy { it.displayOrder }
        .map { WorkOpsBoardColumn(it.id, it.name, it.displayOrder, it.statusIds, it.wipLimit) }
    val statuses get() = statusData?.workOps?.statuses?.all.orEmpty()
    val projects get() = data?.workOps?.projects?.all.orEmpty()
    val memberChoices get() = projects.filter { it.archivedAt == null || it.id in settings?.originalProjectIds.orEmpty() }
    val hiddenMembers get() = settings?.originalProjectIds.orEmpty().filter { id -> projects.none { it.id == id } }
    val swimlanes get() = (listOf("NONE", "ASSIGNEE", "EPIC", "PRIORITY") +
        (if (settings?.multiProject == true) listOf("PROJECT") else emptyList()) + listOfNotNull(settings?.originalSwimlane)).distinct()
    val columnStatusChoices get() = statuses.filter { status ->
        columns.none { it.id != columnDraft?.id && status.id in it.statusIds } || status.id in columnDraft?.originalStatusIds.orEmpty()
    }
    fun statusName(id: String) = statuses.firstOrNull { it.id == id }?.name ?: "Status unavailable"

    suspend fun reload(ctx: RenderContext) {
        columnsLoaded = false
        data = ctx.gql.execute(WorkOpsBoardConfiguration, WorkOpsBoardConfiguration.Variables(id))
        if (tab == "columns" && board != null) statusData = ctx.gql.execute(WorkOpsBoardColumnStatuses, Unit)
        columnsLoaded = true
        if (settings == null && tab == "settings") resetSettings()
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun resetSettings() {
        board?.let { board -> settings = WorkOpsBoardSettingsDraft(board.name, board.type.name, board.swimlaneStrategy.name,
            board.projects.map { it.id }, board.projects.map { it.id }, board.swimlaneStrategy.name, board.version,
            board.projectId == null && board.programId == null && board.portfolioId == null) }
    }
    suspend fun saveSettings(name: String, type: String, swimlane: String, projectsJson: String, ctx: RenderContext) = feedback.perform {
        val inspected = requireNotNull(settings) { "Reload the board settings first." }
        val members = if (inspected.multiProject) (selection(projectsJson) + hiddenMembers).distinct() else inspected.originalProjectIds
        settings = inspected.copy(name = name, type = type, swimlane = swimlane, projectIds = members)
        require(name.isNotBlank()) { "Enter a board name." }
        require(type in setOf("KANBAN", "SCRUM") && swimlane in swimlanes) { "Choose an available board type and swimlane strategy." }
        if (inspected.multiProject) {
            require(members.size >= 2) { "Choose at least two projects." }
            require(members.all { member -> member in inspected.originalProjectIds || projects.any { it.id == member && it.archivedAt == null } }) { "Choose available active projects to add." }
        }
        ctx.gql.execute(WorkOpsUpdateBoard, WorkOpsUpdateBoard.Variables(id, UpdateWorkOpsBoardInput(
            name.trim(), WorkOpsBoardType.valueOf(type), WorkOpsSwimlaneStrategy.valueOf(swimlane), inspected.version,
            addProjectIds = (members - inspected.originalProjectIds.toSet()).takeIf { it.isNotEmpty() },
            removeProjectIds = (inspected.originalProjectIds - members.toSet()).takeIf { it.isNotEmpty() },
        )))
        settings = null
        reload(ctx)
        feedback.message = "Board settings saved."
    }

    fun addColumn() {
        require(columnsLoaded && board != null) { "Reload columns first." }
        columnDraft = WorkOpsBoardColumnDraft(order = Math.addExact(columns.maxOfOrNull { it.order } ?: 0, 1))
        feedback.failed = false; feedback.message = ""
    }
    fun editColumn(id: String) {
        require(columnsLoaded) { "Reload columns first." }
        val column = requireNotNull(columns.firstOrNull { it.id == id }) { "This column is unavailable. Reload columns." }
        columnDraft = WorkOpsBoardColumnDraft(column.id, column.name, column.order, column.statusIds, column.wipLimit?.toString().orEmpty(), column.statusIds)
        feedback.failed = false; feedback.message = ""
    }
    fun cancelColumn() { columnDraft = null }
    suspend fun saveColumn(name: String, statusesJson: String, limit: String, ctx: RenderContext) = feedback.perform {
        val inspected = requireNotNull(columnDraft) { "Open a column editor first." }
        val selected = selection(statusesJson)
        columnDraft = inspected.copy(name = name, statusIds = selected, limit = limit)
        require(name.isNotBlank()) { "Enter a column name." }
        require(selected.isNotEmpty() && selected.all { id -> columnStatusChoices.any { it.id == id } || id in inspected.originalStatusIds }) { "Choose at least one available status." }
        val wip = if (limit.isBlank()) null else requireNotNull(limit.toIntOrNull()?.takeIf { it >= 0 }) { "Enter a nonnegative WIP limit or leave it empty." }.takeIf { it > 0 }
        if (inspected.id.isBlank()) ctx.gql.execute(WorkOpsCreateBoardColumn, WorkOpsCreateBoardColumn.Variables(
            CreateWorkOpsBoardColumnInput(id, name.trim(), inspected.order, selected, wip)))
        else updateColumn(inspected.id, name.trim(), inspected.order, selected, wip, ctx)
        columnDraft = null
        reload(ctx)
        feedback.message = "Column saved."
    }
    fun beginDelete(id: String) {
        require(columnsLoaded) { "Reload columns first." }
        deleteTarget = requireNotNull(columns.firstOrNull { it.id == id }) { "Reload this column first." }
        feedback.failed = false; feedback.message = ""
    }
    fun cancelDelete() { deleteTarget = null }
    suspend fun deleteColumn(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleteTarget) { "Choose a column first." }
        require(confirmed) { "Confirm deleting this column." }
        ctx.gql.execute(WorkOpsDeleteBoardColumn, WorkOpsDeleteBoardColumn.Variables(target.id))
        deleteTarget = null
        reload(ctx)
        feedback.message = "Column deleted."
    }
    suspend fun moveColumn(id: String, direction: Int, ctx: RenderContext) = feedback.perform {
        require(columnsLoaded) { "Reload columns first." }
        require(direction in setOf(-1, 1)) { "Choose an adjacent column." }
        val items = columns
        val index = items.indexOfFirst { it.id == id }
        val from = requireNotNull(items.getOrNull(index)) { "Reload this column first." }
        val to = requireNotNull(items.getOrNull(index + direction)) { "This column has no neighbor in that direction." }
        val temporary = Math.addExact(items.maxOf { it.order }, 1)
        columnsLoaded = false
        try {
            updateColumn(from.id, from.name, temporary, from.statusIds, from.wipLimit, ctx)
            updateColumn(to.id, to.name, from.order, to.statusIds, to.wipLimit, ctx)
            updateColumn(from.id, from.name, to.order, from.statusIds, from.wipLimit, ctx)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            try { reload(ctx) }
            catch (reloadFailure: CancellationException) { throw reloadFailure }
            catch (reloadFailure: Exception) { failure.addSuppressed(reloadFailure) }
            throw failure
        }
        reload(ctx)
        feedback.message = "Column order saved."
    }
    private suspend fun updateColumn(id: String, name: String, order: Int, statuses: List<String>, limit: Int?, ctx: RenderContext) {
        ctx.gql.execute(WorkOpsUpdateBoardColumn, WorkOpsUpdateBoardColumn.Variables(UpdateWorkOpsBoardColumnInput(id, name, order, statuses, limit)))
    }
    private fun selection(value: String): List<String> = if (value.isBlank()) emptyList() else Json.decodeFromString(ListSerializer(String.serializer()), value).distinct()
}

suspend fun workOpsBoardConfiguration(): WorkOpsBoardConfigurationModel {
    val ctx = currentRenderContext()
    val tab = ctx.query["tab"].takeIf { it in setOf("board", "sprints", "columns", "settings") } ?: "board"
    return WorkOpsBoardConfigurationModel(ctx.params["id"].orEmpty(), tab).also { it.retry(ctx) }
}
