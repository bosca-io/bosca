package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.encodeClientState
import bosca.bml.render.decodeClientState
import bosca.workops.ui.generated.WorkopsBoardViewModelStateDispatcher
import bosca.workops.ui.generated.WorkopsBoardViewComponent
import bosca.workops.ui.graphql.WorkOpsBoardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsBoardViewTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(renderLiveStateView = false, gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private val scope = json("""{"workOps":{
      "boards":{"board":{"id":"board","name":"Delivery <board>","type":"KANBAN","swimlaneStrategy":"NONE","projectId":null,"programId":null,"portfolioId":null,
        "projects":[{"id":"project"},{"id":"hidden"}],"columns":[
          {"id":"done","name":"Done","displayOrder":3,"statusIds":["cancelled-status","done-status"],"wipLimit":null},
          {"id":"todo","name":"To do","displayOrder":1,"statusIds":["todo-status"],"wipLimit":1},
          {"id":"doing","name":"In progress","displayOrder":2,"statusIds":["review-status","doing-status"],"wipLimit":1}]}},
      "projects":{"all":[{"id":"project","key":"PLAN","name":"Planning","archivedAt":null,"program":{"id":"program","portfolio":{"id":"portfolio"}}},
        {"id":"other","key":"OTHER","name":"Other","archivedAt":null,"program":{"id":"program","portfolio":{"id":"portfolio"}}}]},
      "tasks":{"resolutions":[{"id":"resolution","name":"Done"}]}}}""")
    private fun taskJson(id: String = "card", version: Long = 3, status: String = "todo-status", project: String = "project") = json("""{
      "id":"$id","key":"PLAN-42","summary":"Write the proposal","version":$version,"assigneeProfileId":"private-profile","assignee":null,
      "project":{"id":"$project"},"priority":{"name":"High"},"status":{"id":"$status","name":"State","category":"TODO"},
      "transitions":[{"id":"start","name":"Start","toState":{"status":{"id":"doing-status","category":"IN_PROGRESS"}}},
        {"id":"resolve","name":"Resolve","toState":{"status":{"id":"done-status","category":"DONE"}}}]}""")
    private fun search(vararg tasks: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("savedFilters") { putJsonObject("searchTasks") { put("rows", JsonArray(tasks.toList())) } } } }
    private fun moved(task: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject("tasks") { put("transition", task) } } }
    private fun sprints(taskIds: List<String> = emptyList()) = buildJsonObject { putJsonObject("workOps") { putJsonObject("sprints") { putJsonArray("byBoard") {
        addJsonObject { put("id", "sprint"); put("name", "Iteration"); put("state", "ACTIVE"); put("endDate", JsonNull); put("committedTaskIds", JsonArray(taskIds.map(::JsonPrimitive))); put("addedDuringSprintTaskIds", JsonArray(emptyList())) }
    } } } }
    private fun model() = WorkOpsBoardViewModel("board", data = WorkOpsBoardView.decodeData(scope), sprintId = "all")

    @Test fun `board scope resolves direct project program and portfolio membership independently`() {
        val base = scope.toString().replace("\"projects\":[{\"id\":\"project\"},{\"id\":\"hidden\"}]", "\"projects\":[]")
        for ((key, value, expected) in listOf(Triple("projectId", "project", listOf("project")),
            Triple("programId", "program", listOf("project", "other")), Triple("portfolioId", "portfolio", listOf("project", "other")),
            Triple("programId", "unavailable", emptyList()))) {
            val payload = Json.parseToJsonElement(base.replace("\"$key\":null", "\"$key\":\"$value\""))
            val page = assertWorkOpsSerialization(WorkOpsBoardViewModel.serializer(), """{"id":"board"}""", """{
              "id":"board","data":$payload,"sprintId":"all","cardsLoaded":true
            }""")
            assertEquals(expected, page.projects.map { it.id })
            assertEquals(expected.isNotEmpty(), page.canQuickAdd)
        }
        val page = model()
        page.data = requireNotNull(page.data).copy(workOps = requireNotNull(page.data).workOps.copy(
            boards = requireNotNull(page.data).workOps.boards.copy(board = null)))
        assertEquals(emptyList(), page.projects)
        assertEquals("Board unavailable", page.title)
        assertFalse(page.canQuickAdd)
    }
    private suspend fun loaded(): WorkOpsBoardViewModel = model().also { page -> page.reload(context { operation, _ -> when (operation) {
        "WorkOpsBoardView" -> scope
        "WorkOpsBoardViewSprints" -> sprints()
        "WorkOpsBoardCards" -> search(taskJson())
        else -> error(operation)
    } }) }

    @Test fun `columns use actual transition statuses and authorized project roots`() = runTest {
        val page = loaded(); val card = page.cards.single()
        assertEquals(listOf("todo", "doing", "done"), page.columns.map { it.id })
        assertEquals(listOf("doing", "done"), page.targets(card).map { it.id })
        assertEquals(listOf("project"), page.projects.map { it.id })
        assertEquals("project IN (\"project\", \"hidden\") ORDER BY priority ASC", page.source)
        assertEquals("Profile unavailable", card.assigneeLabel)
        page.move("card", "doing", 3, context { operation, variables ->
            assertEquals("WorkOpsMoveBoardCard", operation)
            assertEquals("start", variables.getValue("transitionId").jsonPrimitive.content)
            assertEquals(3L, variables.getValue("version").jsonPrimitive.long)
            moved(taskJson(version = 4, status = "doing-status"))
        })
        assertEquals("doing-status", page.cards.single().statusId)
        assertEquals(card.bodyKey, page.cards.single().bodyKey, "A status/version change retains the shared card and pending move")
    }
    @Test fun `stale unavailable and same-column moves fail without sending a write`() = runTest {
        val page = loaded(); val ctx = context { operation, _ -> error("Unexpected $operation") }
        assertFailsWith<IllegalArgumentException> { page.move("card", "doing", 2, ctx) }
        assertFailsWith<IllegalArgumentException> { page.move("card", "todo", 3, ctx) }
        assertFailsWith<IllegalArgumentException> { page.move("card", "unknown", 3, ctx) }
        assertFailsWith<IllegalArgumentException> { page.move("missing", "doing", 3, ctx) }
        assertFailsWith<IllegalArgumentException> { page.move("card", "done", 3, ctx) }
        assertEquals("todo-status", page.cards.single().statusId)
    }
    @Test fun `overlapping stored mappings render each card once and move to its actual first matching column`() = runTest {
        val page = loaded()
        page.data = WorkOpsBoardView.decodeData(json(scope.toString().replace("\"cancelled-status\",\"done-status\"", "\"doing-status\",\"cancelled-status\",\"done-status\"")))
        page.cards = page.cards.map { it.copy(statusId = "doing-status") }
        assertEquals(1, page.columns.sumOf { page.cardsFor(it).size })
        assertEquals("doing", page.columns.single { page.cardsFor(it).isNotEmpty() }.id)
        assertEquals(listOf("done"), page.targets(page.cards.single()).map { it.id })
        page.prepareMove("card", "done", 3)
        assertEquals("resolve", page.pendingMove?.transitionId)
    }
    @Test fun `denied moves keep the inspected card and expose the failure to shared retry feedback`() = runTest {
        val page = loaded()
        val failure = assertFailsWith<IllegalStateException> { page.move("card", "doing", 3, context { _, _ -> error("Permission denied") }) }
        assertEquals("Permission denied", failure.message)
        assertEquals(3L, page.cards.single().version)
        assertEquals("todo-status", page.cards.single().statusId)
        assertFailsWith<CancellationException> { page.move("card", "doing", 3, context { _, _ -> throw CancellationException("left") }) }
    }
    @Test fun `closing moves retain their inspected version and selected resolution after rejection`() = runTest {
        val page = loaded(); page.prepareMove("card", "done", 3)
        page.cards = page.cards.map { it.copy(version = 7) }
        page.completeMove("resolution", context { operation, variables ->
            assertEquals("WorkOpsMoveBoardCard", operation)
            assertEquals(3L, variables.getValue("version").jsonPrimitive.long)
            assertEquals("resolve", variables.getValue("transitionId").jsonPrimitive.content)
            assertEquals("resolution", variables.getValue("resolutionId").jsonPrimitive.content)
            error("A newer task was saved")
        })
        assertTrue(page.feedback.failed); assertEquals("resolution", page.resolutionId)
        assertNotNull(page.pendingMove); assertEquals(3L, page.pendingMove?.version); assertEquals(0L, page.moveSequence)
        page.completeMove("unknown", context { operation, _ -> error(operation) })
        assertEquals("Choose a resolution.", page.feedback.message)
        page.cancelMove(); assertNull(page.pendingMove); assertEquals("canceled", page.moveResult); assertEquals(1L, page.moveSequence)
        page.cancelMove(); assertEquals(1L, page.moveSequence)
    }
    @Test fun `confirmed closing moves report success only after the owning API returns`() = runTest {
        val page = loaded(); page.prepareMove("card", "done", 3)
        page.completeMove("resolution", context { _, _ -> moved(taskJson(version = 4, status = "done-status")) })
        assertFalse(page.feedback.failed); assertNull(page.pendingMove); assertEquals("saved", page.moveResult)
        assertEquals(1L, page.moveSequence); assertEquals("done-status", page.cards.single().statusId)
    }
    @Test fun `an empty active sprint displays no tasks and never falls back to the full board`() = runTest {
        val page = WorkOpsBoardViewModel("board")
        page.reload(context { operation, _ -> when (operation) {
            "WorkOpsBoardView" -> scope
            "WorkOpsBoardViewSprints" -> sprints()
            else -> error("Empty sprint must not request $operation")
        } })
        assertEquals("sprint", page.sprintId); assertTrue(page.cards.isEmpty())
        page.filterSprint("missing", context { operation, _ -> error(operation) })
        assertTrue(page.feedback.failed); assertTrue(page.cards.isEmpty()); assertFalse(page.cardsLoaded); assertFalse(page.canQuickAdd)
    }
    @Test fun `sprint snapshots resolve tasks under current access and restrict them to authorized board projects`() = runTest {
        val page = WorkOpsBoardViewModel("board")
        page.reload(context { operation, variables -> when (operation) {
            "WorkOpsBoardView" -> scope
            "WorkOpsBoardViewSprints" -> sprints(listOf("allowed", "denied", "outside"))
            "WorkOpsBoardTask" -> {
                val id = variables.getValue("id").jsonPrimitive.content
                buildJsonObject { putJsonObject("workOps") { putJsonObject("tasks") { put("task", when (id) {
                    "denied" -> JsonNull
                    "outside" -> taskJson(id = id, project = "other")
                    else -> taskJson(id = id)
                }) } } }
            }
            else -> error(operation)
        } })
        assertEquals(listOf("allowed"), page.cards.map { it.id })
        page.cards = page.cards + page.cards.single().copy(id = "second")
        assertTrue(page.exceedsLimit(page.columns.first()))
        assertEquals("2 tasks · WIP limit 1", page.countLabel(page.columns.first()))
    }
    @Test fun `generated tracking summaries are hidden and authored summaries remain intact`() {
        val card = WorkOpsBoardCard("card", "PLAN-42", "REQ PLAN-REQ-7", 3, "project", "status", "To do", "High", null, null, emptyList())
        assertEquals("Requirement", card.label)
        assertEquals("Review PLAN-REQ-7 with the team", card.copy(summary = "Review PLAN-REQ-7 with the team").label)
    }

    @Test fun `task level access survives a hidden member project without exposing its name`() = runTest {
        val page = WorkOpsBoardViewModel("board")
        page.reload(context { operation, variables -> when (operation) {
            "WorkOpsBoardView" -> scope
            "WorkOpsBoardViewSprints" -> sprints(listOf("allowed", "member-task", "denied"))
            "WorkOpsBoardTask" -> {
                val id = variables.getValue("id").jsonPrimitive.content
                buildJsonObject { putJsonObject("workOps") { putJsonObject("tasks") {
                    put("task", if (id == "denied") JsonNull else taskJson(id = id, project = if (id == "member-task") "hidden" else "project"))
                } } }
            }
            else -> error(operation)
        } })
        assertEquals(listOf("allowed", "member-task"), page.cards.map { it.id })
        assertEquals(listOf("project"), page.projects.map { it.id }, "Project labels must still use authorized roots")
        page.filterSprint("all", context { operation, _ ->
            assertEquals("WorkOpsBoardCards", operation)
            search(taskJson("allowed"), taskJson("member-task", project = "hidden"), taskJson("outside", project = "other"))
        })
        assertEquals(listOf("allowed", "member-task"), page.cards.map { it.id })
    }
    @Test fun `compiled island dispatches both programmatic movement actions into WorkOps state`() = runTest {
        val page = loaded()
        val staged = WorkopsBoardViewModelStateDispatcher.dispatch(
            RenderContext(renderLiveStateView = false), "prepareMove", encodeClientState(page),
            buildJsonArray { add("card"); add("done"); add(3) }, "board",
        )
        assertEquals("resolve", decodeClientState<WorkOpsBoardViewModel>(requireNotNull(staged.state)).pendingMove?.transitionId)
        val moved = WorkopsBoardViewModelStateDispatcher.dispatch(context { operation, variables ->
            assertEquals("WorkOpsMoveBoardCard", operation); assertEquals(3L, variables.getValue("version").jsonPrimitive.long)
            moved(taskJson(version = 4, status = "doing-status"))
        }, "move", encodeClientState(page), buildJsonArray { add("card"); add("doing"); add(3) }, "board")
        assertEquals("doing-status", decodeClientState<WorkOpsBoardViewModel>(requireNotNull(moved.state)).cards.single().statusId)
    }
    @Test fun `quick add retains denied drafts and sends only an available board project and selected sprint`() = runTest {
        val page = loaded(); page.sprintId = "sprint"; page.beginQuickAdd()
        page.quickAdd("A new task", "other", context { operation, _ -> error(operation) })
        assertTrue(page.feedback.failed); assertEquals("Choose an available project in this board.", page.feedback.message)
        page.quickAdd("A new task", "project", context { operation, variables ->
            assertEquals("WorkOpsQuickAddBoardTask", operation)
            assertEquals("project", variables.getValue("projectId").jsonPrimitive.content)
            assertEquals("sprint", variables.getValue("sprintId").jsonPrimitive.content)
            error("Create permission denied")
        })
        assertTrue(page.adding); assertEquals("A new task", page.summaryDraft); assertEquals("project", page.projectDraft)
        page.quickAdd("A new task", "project", context { operation, _ -> when (operation) {
            "WorkOpsQuickAddBoardTask" -> buildJsonObject { putJsonObject("workOps") { putJsonObject("tasks") { put("create", taskJson(id = "created")) } } }
            "WorkOpsBoardView" -> scope
            "WorkOpsBoardViewSprints" -> sprints(listOf("created"))
            "WorkOpsBoardTask" -> buildJsonObject { putJsonObject("workOps") { putJsonObject("tasks") { put("task", taskJson(id = "created")) } } }
            else -> error(operation)
        } })
        assertFalse(page.feedback.failed); assertTrue(page.cardsLoaded)
        assertFalse(page.adding); assertEquals("created", page.createdId)
        assertEquals("/workops/tasks/created", page.createdHref)
        assertEquals("created", page.cards.first().id)
        assertEquals(listOf("created"), page.selectedSprint?.taskIds)
        page.sprints = page.sprints.map { it.copy(state = "CLOSED") }
        assertFalse(page.canQuickAdd)
    }
    @Test fun `compiled domain view uses shared board controls with independent instance keys and escaped content`() = runTest {
        val page = loaded().apply { cards = cards.map { it.copy(summary = "Review <proposal>") } }
        val ctx = RenderContext()
        for (instance in listOf("standalone", "project")) WorkopsBoardViewComponent.render(ctx, mapOf("initial" to page, "instance" to instance)) {}
        val html = ctx.writer.toString()
        assertContains(html, "id=\"workops-board-standalone\"")
        assertContains(html, "id=\"workops-board-project\"")
        assertContains(html, "data-workops-closing-columns=\"done\"")
        assertContains(html, "data-workops-card-version=\"3\"")
        assertContains(html, "Review &lt;proposal&gt;"); assertFalse(html.contains("Review <proposal>"))
        assertContains(html, "data-bml-scroll=\"board-column-standalone-todo\"")
        assertContains(html, "data-bml-scroll=\"board-column-project-todo\"")
    }
    @Test fun `failed reads do not render an empty board or advertise task counts`() = runTest {
        val page = loaded()
        page.retry(context { operation, _ -> if (operation == "WorkOpsBoardView") scope else error("Task service unavailable") })
        assertTrue(page.feedback.failed); assertFalse(page.cardsLoaded)
        val ctx = RenderContext()
        WorkopsBoardViewComponent.render(ctx, mapOf("initial" to page, "instance" to "unavailable")) {}
        val html = ctx.writer.toString()
        assertContains(html, "Task service unavailable"); assertContains(html, "Tasks have not been loaded")
        assertFalse(html.contains("data-bml-board")); assertFalse(html.contains("No tasks."))
    }
}
