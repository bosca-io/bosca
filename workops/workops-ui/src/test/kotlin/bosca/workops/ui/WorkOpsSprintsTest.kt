package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.*

class WorkOpsSprintsTest {
    @Test fun `a current sprint remains inspectable outside the list while absent identities cannot start`() = runTest {
        val current = sprint()
        val model = WorkOpsSprintsModel("detail", "board", current = current)
        model.beginStart("sprint", context { _, _ -> error("Choices unavailable") })
        assertEquals(current, model.startDraft?.sprint)
        assertFalse(requireNotNull(model.startDraft).ready)
        model.cancelStart()
        model.beginStart("missing", context { _, _ -> throw AssertionError("Missing sprint cannot read") })
        assertEquals("Reload this sprint first.", model.feedback.message)
        model.current = null
        model.beginStart("missing", context { _, _ -> throw AssertionError("Missing sprint cannot read") })
        assertEquals("Reload this sprint first.", model.feedback.message)
    }

    @Test fun `completion retains unready and invalid destination drafts without submitting`() = runTest {
        val active = sprint(state = "ACTIVE")
        val model = listModel(active)
        val noWrites = context { _, _ -> throw AssertionError("Invalid completion cannot write") }
        model.closeDraft = WorkOpsSprintCloseDraft(active, tasks = listOf(task()), ready = false)
        model.close("", "", noWrites)
        assertEquals("Reload the sprint tasks before completing.", model.feedback.message)
        model.closeDraft = requireNotNull(model.closeDraft).copy(ready = true)
        model.close("", "{}", noWrites)
        assertEquals("Choose a destination for each unfinished task.", model.feedback.message)
        model.close("", """{"task":"missing"}""", noWrites)
        assertEquals("Choose an available future sprint or the backlog.", model.feedback.message)
        model.closeDraft = WorkOpsSprintCloseDraft(active, tasks = listOf(task(category = "DONE")), ready = true)
        model.close("", "", context { operation, variables ->
            assertEquals("WorkOpsCloseSprint", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(JsonNull, input["velocityPoints"] ?: JsonNull)
            assertEquals(JsonArray(emptyList()), input["destinations"])
            error("Denied")
        })
        assertEquals("Denied", model.feedback.message)
        assertNotNull(model.closeDraft)
    }
    private fun sprint(id: String = "sprint", state: String = "FUTURE", committed: List<String> = emptyList(), added: List<String> = emptyList(), version: Long = 5) =
        WorkOpsSprintRecord(id, "Iteration $id", state, "A goal", null, "2026-11-20T00:00:00Z", null, committed, added, null,
            "board", "Planning board", "SCRUM", version, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z")
    private fun task(id: String = "task", category: String = "TODO", version: Long = 3) = WorkOpsSprintTask(id, "PLAN-7", "Write a proposal", version,
        status = "State", category = category, priority = "Medium", priorityColor = "#334455")
    private fun sprintJson(sprint: WorkOpsSprintRecord) = buildJsonObject {
        put("id", sprint.id); put("name", sprint.name); put("state", sprint.state); put("goal", sprint.goal)
        put("startDate", sprint.startDate?.let(::JsonPrimitive) ?: JsonNull); put("endDate", sprint.endDate?.let(::JsonPrimitive) ?: JsonNull)
        put("completeDate", sprint.completeDate?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("committedTaskIds") { sprint.committedIds.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("addedDuringSprintTaskIds") { sprint.addedIds.forEach { add(JsonPrimitive(it)) } }
        put("velocityPoints", sprint.velocity?.let(::JsonPrimitive) ?: JsonNull); put("boardId", sprint.boardId); put("version", sprint.version)
        put("createdAt", sprint.created); put("modifiedAt", sprint.modified)
        putJsonObject("board") { put("id", sprint.boardId); put("name", sprint.boardName); put("type", sprint.boardType) }
    }
    private fun taskJson(task: WorkOpsSprintTask) = buildJsonObject {
        put("id", task.id); put("key", task.key); put("summary", task.summary); put("version", task.version)
        put("sprintId", task.sprintId?.let(::JsonPrimitive) ?: JsonNull); put("assigneeProfileId", task.assigneeId?.let(::JsonPrimitive) ?: JsonNull)
        put("assignee", if (task.assigneeName.isBlank()) JsonNull else buildJsonObject { put("name", task.assigneeName) })
        putJsonObject("status") { put("id", "status-${task.category}"); put("name", task.status); put("category", task.category) }
        putJsonObject("priority") { put("name", task.priority); put("colorHex", task.priorityColor) }
    }
    private fun data(namespace: String, field: String, value: JsonElement) = buildJsonObject { putJsonObject("workOps") { putJsonObject(namespace) { put(field, value) } } }
    private fun searches(vararg tasks: WorkOpsSprintTask) = data("savedFilters", "searchTasks", buildJsonObject { put("rows", JsonArray(tasks.map(::taskJson))) })
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun listModel(sprint: WorkOpsSprintRecord = sprint()) = WorkOpsSprintsModel("board", "board", boardName = "Planning board", rows = listOf(sprint), terminalStatusIds = listOf("done-status", "cancelled-status"))

    @Test fun `start retains prior commitment and unavailable selections while paging and searching`() = runTest {
        val planned = sprint(committed = listOf("planned", "hidden"))
        val model = listModel(planned)
        val ctx = context { operation, variables -> when (operation) {
            "WorkOpsSprintTaskInfo" -> data("tasks", "task", variables.getValue("id").jsonPrimitive.content.let { if (it == "hidden") JsonNull else taskJson(task(it)) })
            "WorkOpsSprintSearchTasks" -> {
                val offset = variables.getValue("offset").jsonPrimitive.long
                if (offset > 0) searches(task("later")) else searches(*(0..25).map { task("candidate-$it") }.toTypedArray())
            }
            else -> error(operation)
        } }
        model.beginStart(planned.id, ctx)
        assertEquals(listOf("planned", "hidden"), model.startDraft?.selectedIds)
        assertEquals(1, model.startDraft?.unavailableCount); assertTrue(requireNotNull(model.startDraft).hasNext)
        model.searchStart("2028-05-02", "", "", "[\"planned\",\"hidden\",\"candidate-1\"]", 25, ctx)
        val draft = requireNotNull(model.startDraft)
        assertEquals(25L, draft.offset); assertEquals("2028-05-02", draft.startDate); assertEquals("", draft.endDate)
        assertEquals(listOf("planned", "hidden", "candidate-1"), draft.selectedIds)
        assertTrue(draft.options.any { it.value == "candidate-1" }); assertTrue(draft.options.any { it.value == "later" })
        assertTrue(draft.options.single { it.value == "hidden" }.label.startsWith("Unavailable selected task"))
    }

    @Test fun `start sends the inspected version UTC dates and an explicitly emptied commitment without losing a rejected draft`() = runTest {
        val planned = sprint(committed = listOf("planned"))
        val model = listModel(planned)
        model.beginStart(planned.id, context { operation, _ -> when (operation) {
            "WorkOpsSprintTaskInfo" -> data("tasks", "task", taskJson(task("planned")))
            "WorkOpsSprintSearchTasks" -> searches()
            else -> error(operation)
        } })
        model.rows = listOf(planned.copy(version = 9))
        model.start("2028-05-02", "", "Retained search", "[]", context { operation, variables ->
            assertEquals("WorkOpsStartSprint", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(5L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals("2028-05-02T00:00Z", input.getValue("startDate").jsonPrimitive.content)
            assertEquals(JsonNull, input["endDate"] ?: JsonNull)
            assertTrue((input["committedTaskIds"] as? JsonArray).orEmpty().isEmpty())
            error("A newer sprint was saved")
        })
        assertTrue(model.feedback.failed); assertEquals(5L, model.startDraft?.sprint?.version)
        assertEquals("Retained search", model.startDraft?.query); assertTrue(requireNotNull(model.startDraft).selectedIds.isEmpty())
    }

    @Test fun `close distinguishes done cancelled hidden and unfinished members and retains rejected destinations`() = runTest {
        val active = sprint(state = "ACTIVE", committed = listOf("open", "done"), added = listOf("cancelled", "hidden"))
        val future = sprint("future")
        val model = listModel(active)
        model.beginClose(active.id, context { operation, variables -> when (operation) {
            "WorkOpsSprintTaskInfo" -> {
                val id = variables.getValue("id").jsonPrimitive.content
                data("tasks", "task", when (id) { "hidden" -> JsonNull; "done" -> taskJson(task(id, "DONE")); "cancelled" -> taskJson(task(id, "CANCELLED")); else -> taskJson(task(id)) })
            }
            "WorkOpsSprintsForBoard" -> data("sprints", "byBoard", JsonArray(listOf(sprintJson(active), sprintJson(future))))
            else -> error(operation)
        } })
        val draft = requireNotNull(model.closeDraft)
        assertEquals(listOf("done", "cancelled"), draft.completed.map { it.id })
        assertEquals(listOf("open", "hidden"), draft.unfinished.map { it.id })
        assertEquals(setOf("open", "hidden"), draft.schema.getValue("properties").jsonObject.keys)
        assertEquals(listOf("Backlog", future.name), draft.options.getValue("open").map { it.label })
        model.rows = listOf(active.copy(version = 8), future)
        model.close("12.5", "{\"open\":\"future\",\"hidden\":\"backlog\"}", context { operation, variables ->
            assertEquals("WorkOpsCloseSprint", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(5L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals(12.5, input.getValue("velocityPoints").jsonPrimitive.double)
            val destinations = input.getValue("destinations").jsonArray.map { it.jsonObject }
            assertEquals(listOf("open", "hidden"), destinations.map { it.getValue("taskId").jsonPrimitive.content })
            assertEquals("future", destinations.first().getValue("targetSprintId").jsonPrimitive.content)
            assertEquals(JsonNull, destinations.last()["targetSprintId"] ?: JsonNull)
            error("Task edit denied")
        })
        assertTrue(model.feedback.failed); assertEquals("12.5", model.closeDraft?.velocity)
        assertEquals(mapOf("open" to "future", "hidden" to "backlog"), model.closeDraft?.destinations)
        assertEquals(5L, model.closeDraft?.sprint?.version)
    }

    @Test fun `completion rejects missing destinations and nonfinite velocity and preserves an unavailable selected sprint`() = runTest {
        val active = sprint(state = "ACTIVE", committed = listOf("open"))
        val model = listModel(active)
        model.closeDraft = WorkOpsSprintCloseDraft(active, tasks = listOf(task("open")), destinations = mapOf("open" to "removed"), ready = true)
        assertEquals("removed", model.closeDraft?.value?.getValue("open")?.jsonPrimitive?.content)
        val unavailable = requireNotNull(model.closeDraft).options.getValue("open").single { it.value == "removed" }
        assertTrue(unavailable.disabled); assertEquals("Selected sprint unavailable", unavailable.label)
        model.close("", "{}", context { _, _ -> error("Must not write") })
        assertEquals("Choose a destination for each unfinished task.", model.feedback.message)
        model.close("NaN", "{\"open\":\"backlog\"}", context { _, _ -> error("Must not write") })
        assertEquals("Enter a finite number for velocity.", model.feedback.message)
        assertEquals("NaN", model.closeDraft?.velocity)
    }

    @Test fun `successful create and start remain directly reachable when refreshing fails`() = runTest {
        val record = sprint()
        val created = listModel().apply { beginCreate() }
        created.create("New iteration", "Retained goal", context { operation, _ ->
            if (operation == "WorkOpsCreateSprint") data("sprints", "create", sprintJson(record)) else error("Read unavailable")
        })
        assertFalse(created.creating); assertTrue(created.feedback.failed)
        assertEquals("/workops/sprints/sprint", created.resultHref)
        val started = listModel()
        started.startDraft = WorkOpsSprintStartDraft(record, "", "", ready = true)
        started.start("", "", "", "[]", context { operation, _ ->
            if (operation == "WorkOpsStartSprint") data("sprints", "start", sprintJson(record.copy(state = "ACTIVE"))) else error("Read unavailable")
        })
        assertNull(started.startDraft); assertTrue(started.feedback.failed)
        assertEquals("/workops/sprints/sprint", started.resultHref)
    }

    @Test fun `task search falls back from an unknown key escapes summaries and keeps inspected add versions`() = runTest {
        val active = sprint(state = "ACTIVE")
        val model = WorkOpsSprintsModel("detail", "board", "sprint", current = active, rows = listOf(active), terminalStatusIds = listOf("done-status"))
        val candidate = task("candidate", version = 7)
        model.search("PLAN-7", 0, context { operation, _ -> when (operation) {
            "WorkOpsSprintTaskByKey" -> data("tasks", "taskByKey", JsonNull)
            "WorkOpsSprintSearchTasks" -> searches(candidate)
            else -> error(operation)
        } })
        assertEquals(listOf("candidate"), model.searchRows.map { it.id })
        model.search("a \"quoted\" title", 25, context { operation, variables ->
            assertEquals("WorkOpsSprintSearchTasks", operation)
            assertEquals(0L, variables.getValue("offset").jsonPrimitive.long, "A changed query starts at the first offset")
            assertEquals("summary ~ \"a \\\"quoted\\\" title\" ORDER BY priority ASC", variables.getValue("source").jsonPrimitive.content)
            searches(candidate)
        })
        model.addTask(candidate.id, "An unsent query draft", context { operation, variables ->
            assertEquals("WorkOpsAddSprintTask", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(7L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals(active.id, input.getValue("sprintId").jsonPrimitive.content)
            error("Task changed")
        })
        assertTrue(model.feedback.failed); assertEquals("An unsent query draft", model.queryDraft)
        assertEquals(listOf(candidate), model.searchRows)
        model.current = active.copy(state = "CLOSED")
        model.addTask(candidate.id, "Keep this query", context { _, _ -> error("Must not write") })
        assertEquals("Closed sprints cannot accept tasks.", model.feedback.message)
    }

    @Test fun `closed snapshot members retain current task access and membership pages`() = runTest {
        val closed = sprint(state = "CLOSED", committed = listOf("done", "hidden"), added = listOf("added", "done"))
        val model = WorkOpsSprintsModel("detail", sprintId = closed.id, memberOffset = 1, terminalStatusIds = listOf("done-status"))
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsSprintInfo" -> data("sprints", "sprint", sprintJson(closed))
            "WorkOpsSprintsForBoard" -> data("sprints", "byBoard", JsonArray(listOf(sprintJson(closed))))
            "WorkOpsSprintTaskInfo" -> {
                val id = variables.getValue("id").jsonPrimitive.content
                data("tasks", "task", when (id) { "hidden" -> JsonNull; "done" -> taskJson(task(id, "DONE")); else -> taskJson(task(id).copy(sprintId = "next-iteration")) })
            }
            else -> error(operation)
        } })
        assertEquals(listOf("done", "hidden", "added"), model.members.map { it.id })
        assertFalse(model.members[1].available); assertTrue(model.members.last().added)
        assertEquals("next-iteration", model.members.last().sprintId)
        assertEquals(1, model.unavailableCount); assertEquals(50, model.completionPercent)
        assertEquals(listOf("hidden", "added"), model.visibleMembers.map { it.id })
        assertFalse(model.canAddTasks)
    }

    @Test fun `sprint lists follow backend offset pages and preserve board and state navigation`() = runTest {
        val records = (0..100).map { sprint("sprint-$it", if (it % 2 == 0) "CLOSED" else "FUTURE") }
        val model = WorkOpsSprintsModel("board", "board", state = "closed", offset = 25, terminalStatusIds = listOf("done-status"))
        val offsets = mutableListOf<Long>()
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsSprintBoard" -> data("boards", "board", buildJsonObject { put("id", "board"); put("name", "Planning board"); put("type", "SCRUM") })
            "WorkOpsSprintsForBoard" -> {
                val offset = variables.getValue("offset").jsonPrimitive.long; offsets += offset
                data("sprints", "byBoard", JsonArray(records.drop(offset.toInt()).take(100).map(::sprintJson)))
            }
            else -> error(operation)
        } })
        assertEquals(listOf(0L, 100L), offsets); assertEquals(25, model.pageRows.size); assertTrue(model.hasNext)
        assertEquals("/workops/boards/board?tab=sprints&state=closed&offset=50", model.nextHref)
        assertEquals("/workops/boards/board?tab=sprints&state=closed&offset=0", model.previousHref)
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("Left sprints") }) }
    }

    @Test fun `remaining time and visible completion follow lifecycle and available task details`() {
        val active = sprint(state = "ACTIVE").copy(endDate = "2026-11-20T00:00:00Z")
        assertEquals("Ends today", active.remaining(OffsetDateTime.parse("2026-11-19T12:00:00Z")))
        assertEquals("1d overdue", active.remaining(OffsetDateTime.parse("2026-11-20T12:00:00Z")))
        assertEquals("", active.copy(state = "CLOSED").remaining())
        val model = listModel(active).apply { members = listOf(task("one", "DONE"), task("two", "DONE"), task("three"), WorkOpsSprintTask("hidden", available = false)) }
        assertEquals(67, model.completionPercent)
        assertEquals("Task", task("authored").copy(summary = "Task").label)
        assertEquals("Requirement", task("legacy").copy(summary = "REQ PLAN-REQ-3").label)
    }
}
