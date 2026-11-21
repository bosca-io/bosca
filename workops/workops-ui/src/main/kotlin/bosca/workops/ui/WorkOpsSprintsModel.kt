package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** The same WorkOps lifecycle and drafts serve the sprint hub, detail and board Sprints view. */
@Serializable
class WorkOpsSprintsModel(
    val mode: String,
    var boardId: String = "",
    val sprintId: String = "",
    val tab: String = "tasks",
    val state: String = "open",
    val offset: Long = 0,
    val memberOffset: Long = 0,
    val projectId: String = "",
    var boardName: String = "",
    var boardType: String = "",
    var rows: List<WorkOpsSprintRecord> = emptyList(),
    var current: WorkOpsSprintRecord? = null,
    var members: List<WorkOpsSprintTask> = emptyList(),
    var membersLoaded: Boolean = false,
    var creating: Boolean = false,
    var nameDraft: String = "",
    var goalDraft: String = "",
    var startDraft: WorkOpsSprintStartDraft? = null,
    var closeDraft: WorkOpsSprintCloseDraft? = null,
    var queryDraft: String = "",
    var searchOffset: Long = 0,
    var searchRows: List<WorkOpsSprintTask> = emptyList(),
    var searchHasNext: Boolean = false,
    var terminalStatusIds: List<String> = emptyList(),
    var resultId: String = "",
    var resultLabel: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val title get() = if (mode == "detail") current?.name ?: "Sprint unavailable" else "Sprints"
    val resultHref get() = resultId.takeIf(String::isNotBlank)?.let { "/workops/sprints/${workOpsEncode(it)}" }.orEmpty()
    val active get() = rows.firstOrNull { it.state == "ACTIVE" }
    private val filtered get() = rows.filter { when (state) { "closed" -> it.state == "CLOSED"; "all" -> true; else -> it.state != "CLOSED" } }
        .sortedBy { when (it.state) { "ACTIVE" -> 0; "FUTURE" -> 1; else -> 2 } }
    val pageRows get() = filtered.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val hasNext get() = filtered.size.toLong() > offset + 25
    val visibleMembers get() = members.drop(memberOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val membersHaveNext get() = members.size.toLong() > memberOffset + 25
    val availableMembers get() = members.filter { it.available }
    val doneCount get() = availableMembers.count { it.category == "DONE" }
    val unavailableCount get() = members.count { !it.available }
    val completion get() = if (availableMembers.isEmpty()) 0.0 else doneCount * 100.0 / availableMembers.size
    val completionPercent get() = completion.roundToInt()
    val canCreate get() = boardId.isNotBlank() && boardName.isNotBlank()
    val canAddTasks get() = current?.state in setOf("FUTURE", "ACTIVE")
    val stateTabs get() = listOf("open" to "Active and planned", "closed" to "Closed", "all" to "All")
        .map { (value, label) -> NavigationItem(label, listHref(0, value), state == value) }
    val detailTabs get() = listOf("overview" to "Overview", "tasks" to "Tasks", "progress" to "Progress")
        .map { (value, label) -> NavigationItem(label, "${current?.href.orEmpty()}?tab=$value", tab == value) }
    val previousHref get() = if (offset > 0) listHref((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (hasNext) listHref(offset + 25) else ""
    val previousMembersHref get() = if (memberOffset > 0) "${current?.href}?tab=tasks&memberOffset=${(memberOffset - 25).coerceAtLeast(0)}" else ""
    val nextMembersHref get() = if (membersHaveNext) "${current?.href}?tab=tasks&memberOffset=${memberOffset + 25}" else ""

    private fun listHref(offset: Long, state: String = this.state): String =
        if (mode == "board") "/workops/boards/${workOpsEncode(boardId)}?tab=sprints&state=$state&offset=$offset"
        else "/workops/sprints?projectId=${workOpsEncode(projectId)}&boardId=${workOpsEncode(boardId)}&state=$state&offset=$offset"

    suspend fun reload(ctx: RenderContext) {
        membersLoaded = false
        if (mode == "detail") {
            current = ctx.gql.execute(WorkOpsSprintInfo, WorkOpsSprintInfo.Variables(sprintId)).workOps.sprints.sprint?.sprintRecord()
            if (current == null) { ctx.notFound = true; return }
            boardId = requireNotNull(current).boardId
            boardName = requireNotNull(current).boardName; boardType = requireNotNull(current).boardType
        } else {
            if (boardId.isBlank()) return
            val board = requireNotNull(ctx.gql.execute(WorkOpsSprintBoard, WorkOpsSprintBoard.Variables(boardId)).workOps.boards.board) { "This board is unavailable." }
            boardName = board.name; boardType = board.type.name
        }
        rows = loadRows(ctx)
        if (mode == "detail") current = rows.firstOrNull { it.id == sprintId } ?: current
        if (terminalStatusIds.isEmpty()) terminalStatusIds = ctx.gql.execute(WorkOpsBoardColumnStatuses, Unit).workOps.statuses.all
            .filter { it.category in setOf(WorkOpsStatusCategory.DONE, WorkOpsStatusCategory.CANCELLED) }.map { it.id }
        if (mode == "detail" && tab in setOf("tasks", "progress")) {
            members = readMembers(requireNotNull(current), ctx)
            membersLoaded = true
        }
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    private suspend fun loadRows(ctx: RenderContext): List<WorkOpsSprintRecord> = buildList {
        var offset = 0L
        do {
            val page = ctx.gql.execute(WorkOpsSprintsForBoard, WorkOpsSprintsForBoard.Variables(boardId, offset)).workOps.sprints.byBoard
            addAll(page.map { it.sprintRecord() }); offset += page.size
        } while (page.size == 100)
    }
    private suspend fun readMembers(sprint: WorkOpsSprintRecord, ctx: RenderContext): List<WorkOpsSprintTask> = buildList {
        for (id in sprint.memberIds) {
            val added = id !in sprint.committedIds
            add(ctx.gql.execute(WorkOpsSprintTaskInfo, WorkOpsSprintTaskInfo.Variables(id)).workOps.tasks.task?.sprintTask(added)
                ?: WorkOpsSprintTask(id, added = added, available = false))
        }
    }
    private fun inspected(id: String) = requireNotNull(rows.firstOrNull { it.id == id } ?: current?.takeIf { it.id == id }) { "Reload this sprint first." }

    fun beginCreate() { creating = true; nameDraft = ""; goalDraft = ""; resultId = ""; feedback.failed = false; feedback.message = "" }
    fun cancelCreate() { creating = false }
    suspend fun create(name: String, goal: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name; goalDraft = goal
        require(creating && canCreate) { "Choose an available board first." }
        require(name.isNotBlank()) { "Enter a sprint name." }
        val created = ctx.gql.execute(WorkOpsCreateSprint, WorkOpsCreateSprint.Variables(CreateWorkOpsSprintInput(boardId, name.trim(), goal.trim().takeIf(String::isNotBlank)))).workOps.sprints.create.sprintRecord()
        creating = false; resultId = created.id; resultLabel = "Open the created sprint"
        reload(ctx)
        feedback.message = "Sprint created."
    }

    suspend fun beginStart(id: String, ctx: RenderContext) = feedback.perform {
        val sprint = inspected(id)
        require(sprint.state == "FUTURE" && active == null) { "Only a planned sprint can start when the board has no active sprint." }
        val today = LocalDate.now(ZoneOffset.UTC)
        startDraft = WorkOpsSprintStartDraft(sprint, today.toString(), today.plusDays(14).toString(), selectedIds = sprint.committedIds.distinct())
        refreshStart(ctx)
    }
    fun cancelStart() { startDraft = null }
    private fun captureStart(startDate: String, endDate: String, query: String, selectedJson: String) {
        val draft = requireNotNull(startDraft) { "Open the start dialog first." }
        val selected = selection(selectedJson)
        val offered = (draft.selectedIds + draft.candidates.map { it.id }).toSet()
        require(selected.all { it in offered }) { "Choose tasks from the available or selected results." }
        val tasks = (draft.selectedTasks + draft.candidates).distinctBy { it.id }.filter { it.id in selected }
        startDraft = draft.copy(startDate = startDate, endDate = endDate, query = query, selectedIds = selected, selectedTasks = tasks)
    }
    private suspend fun refreshStart(ctx: RenderContext) {
        val draft = requireNotNull(startDraft)
        startDraft = draft.copy(ready = false)
        val selected = mutableListOf<WorkOpsSprintTask>()
        for (id in draft.selectedIds) selected += ctx.gql.execute(WorkOpsSprintTaskInfo, WorkOpsSprintTaskInfo.Variables(id)).workOps.tasks.task?.sprintTask()
            ?: WorkOpsSprintTask(id, available = false)
        val (candidates, more) = taskChoices(draft.query, draft.offset, true, ctx)
        startDraft = draft.copy(selectedTasks = selected, candidates = candidates, hasNext = more, ready = true)
    }
    suspend fun searchStart(startDate: String, endDate: String, query: String, selectedJson: String, offset: Long, ctx: RenderContext) = feedback.perform {
        val oldQuery = startDraft?.query.orEmpty()
        captureStart(startDate, endDate, query, selectedJson)
        startDraft = requireNotNull(startDraft).copy(offset = if (oldQuery.trim() == query.trim()) offset.coerceAtLeast(0) else 0)
        refreshStart(ctx)
    }
    suspend fun start(startDate: String, endDate: String, query: String, selectedJson: String, ctx: RenderContext) = feedback.perform {
        captureStart(startDate, endDate, query, selectedJson)
        val draft = requireNotNull(startDraft)
        require(draft.ready) { "Reload the available tasks before starting." }
        val started = ctx.gql.execute(WorkOpsStartSprint, WorkOpsStartSprint.Variables(StartWorkOpsSprintInput(
            sprintId = draft.sprint.id, expectedVersion = draft.sprint.version, startDate = date(startDate), endDate = date(endDate), committedTaskIds = draft.selectedIds,
        ))).workOps.sprints.start.sprintRecord()
        startDraft = null; resultId = started.id; resultLabel = "Open the started sprint"
        reload(ctx)
        feedback.message = "Sprint started."
    }

    suspend fun beginClose(id: String, ctx: RenderContext) = feedback.perform {
        val sprint = inspected(id)
        require(sprint.state == "ACTIVE") { "Only an active sprint can be completed." }
        closeDraft = WorkOpsSprintCloseDraft(sprint, velocity = sprint.velocity?.toString().orEmpty())
        refreshClose(ctx)
    }
    fun cancelClose() { closeDraft = null }
    private suspend fun refreshClose(ctx: RenderContext) {
        val draft = requireNotNull(closeDraft)
        closeDraft = draft.copy(ready = false)
        val tasks = readMembers(draft.sprint, ctx)
        val futures = loadRows(ctx).filter { it.state == "FUTURE" && it.id != draft.sprint.id }.map { WorkOpsSprintChoice(it.id, it.name) }
        val destinations = tasks.filter { !it.available || !it.finished }.associate { it.id to (draft.destinations[it.id] ?: "backlog") }
        closeDraft = draft.copy(tasks = tasks, futures = futures, destinations = destinations, ready = true)
    }
    private fun captureClose(velocity: String, destinationsJson: String) {
        val draft = requireNotNull(closeDraft) { "Open the completion dialog first." }
        val destinations = if (destinationsJson.isBlank()) emptyMap() else Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), destinationsJson)
        closeDraft = draft.copy(velocity = velocity, destinations = destinations)
    }
    suspend fun retryClose(velocity: String, destinationsJson: String, ctx: RenderContext) = feedback.perform {
        captureClose(velocity, destinationsJson); refreshClose(ctx)
    }
    suspend fun close(velocity: String, destinationsJson: String, ctx: RenderContext) = feedback.perform {
        captureClose(velocity, destinationsJson)
        val draft = requireNotNull(closeDraft)
        require(draft.ready) { "Reload the sprint tasks before completing." }
        require(draft.destinations.keys == draft.unfinished.map { it.id }.toSet()) { "Choose a destination for each unfinished task." }
        val allowed = draft.choices.map { it.value }.toSet()
        require(draft.destinations.values.all { it in allowed }) { "Choose an available future sprint or the backlog." }
        val points = if (velocity.isBlank()) null else requireNotNull(velocity.toDoubleOrNull()?.takeIf(Double::isFinite)) { "Enter a finite number for velocity." }
        val completed = ctx.gql.execute(WorkOpsCloseSprint, WorkOpsCloseSprint.Variables(CloseWorkOpsSprintInput(
            sprintId = draft.sprint.id, expectedVersion = draft.sprint.version,
            destinations = draft.unfinished.map { WorkOpsCloseSprintDestination(it.id, draft.destinations.getValue(it.id).takeUnless { value -> value == "backlog" }) },
            velocityPoints = points,
        ))).workOps.sprints.close.sprintRecord()
        closeDraft = null; resultId = completed.id; resultLabel = "Open the completed sprint"
        reload(ctx)
        feedback.message = "Sprint completed."
    }

    suspend fun search(query: String, offset: Long, ctx: RenderContext) = feedback.perform {
        val sameQuery = query.trim() == queryDraft.trim()
        queryDraft = query; searchOffset = if (sameQuery) offset.coerceAtLeast(0) else 0
        require(query.isNotBlank()) { "Enter a task key or summary." }
        require(canAddTasks) { "Closed sprints cannot accept tasks." }
        val (tasks, more) = taskChoices(query, searchOffset, false, ctx)
        val existing = current?.memberIds.orEmpty().toSet()
        searchRows = tasks.filterNot { it.id in existing }; searchHasNext = more
    }
    suspend fun addTask(id: String, query: String, ctx: RenderContext) = feedback.perform {
        queryDraft = query
        val sprint = requireNotNull(current) { "Reload this sprint first." }
        require(canAddTasks) { "Closed sprints cannot accept tasks." }
        val task = requireNotNull(searchRows.firstOrNull { it.id == id }) { "Search for this task again before adding it." }
        ctx.gql.execute(WorkOpsAddSprintTask, WorkOpsAddSprintTask.Variables(id, UpdateWorkOpsTaskInput(sprintId = sprint.id, expectedVersion = task.version)))
        searchRows = searchRows.filterNot { it.id == id }; resultId = sprint.id; resultLabel = "Open the updated sprint"
        reload(ctx)
        feedback.message = "Task added to sprint."
    }
    private suspend fun taskChoices(query: String, offset: Long, onlyOpen: Boolean, ctx: RenderContext): Pair<List<WorkOpsSprintTask>, Boolean> {
        val term = query.trim()
        if (Regex("[A-Za-z][A-Za-z0-9_]{1,9}-[1-9][0-9]*").matches(term)) {
            val task = ctx.gql.execute(WorkOpsSprintTaskByKey, WorkOpsSprintTaskByKey.Variables(term.uppercase())).workOps.tasks.taskByKey?.sprintTask()
            if (task != null) return listOfNotNull(task.takeIf { offset == 0L && (!onlyOpen || !it.finished) }) to false
        }
        val clauses = mutableListOf<String>()
        if (onlyOpen && terminalStatusIds.isNotEmpty()) clauses += "status NOT IN (${terminalStatusIds.joinToString(", ", transform = ::quote)})"
        if (term.isNotBlank()) clauses += "summary ~ ${quote(term)}"
        val source = clauses.joinToString(" AND ").let { if (it.isBlank()) "ORDER BY priority ASC" else "$it ORDER BY priority ASC" }
        val result = ctx.gql.execute(WorkOpsSprintSearchTasks, WorkOpsSprintSearchTasks.Variables(source, offset)).workOps.savedFilters.searchTasks.rows
        return result.take(25).map { it.sprintTask() } to (result.size > 25)
    }
    private fun quote(value: String) = Json.encodeToString(String.serializer(), value)
    private fun selection(value: String) = if (value.isBlank()) emptyList() else Json.decodeFromString(ListSerializer(String.serializer()), value).distinct()
    private fun date(value: String): String? = value.takeIf(String::isNotBlank)?.let { LocalDate.parse(it).atStartOfDay().atOffset(ZoneOffset.UTC).toString() }
}

suspend fun workOpsSprintDetail(): WorkOpsSprintsModel = workOpsSprints("detail", sprintId = currentRenderContext().params["id"].orEmpty())

suspend fun workOpsSprints(mode: String, boardId: String = "", sprintId: String = ""): WorkOpsSprintsModel {
    val ctx = currentRenderContext()
    return WorkOpsSprintsModel(mode, boardId, sprintId,
        ctx.query["tab"].takeIf { it in setOf("tasks", "overview", "progress") } ?: "tasks",
        ctx.query["state"].takeIf { it in setOf("open", "closed", "all") } ?: "open",
        ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["memberOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["projectId"].orEmpty()).also { it.retry(ctx) }
}
