package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsBoardConfigurationTest {
    @Test fun `invalid settings retain the inspected revision and reject new unavailable membership`() = runTest {
        val noWrites = context { _, _ -> throw AssertionError("Invalid board settings cannot write") }
        for ((name, type, swimlane, members) in listOf(
            listOf("", "SCRUM", "NONE", "[]"), listOf("Retained", "UNKNOWN", "NONE", "[]"),
            listOf("Retained", "SCRUM", "UNKNOWN", "[]"), listOf("Retained", "SCRUM", "NONE", "[]"),
            listOf("Retained", "SCRUM", "NONE", "[\"project\",\"missing\"]"),
            listOf("Retained", "SCRUM", "NONE", "[\"project\",\"archived\"]"))) {
            val model = WorkOpsBoardConfigurationModel("board", "settings")
            model.reload(reads(configuration(members = listOf("project", "other"))))
            model.saveSettings(name, type, swimlane, members, noWrites)
            assertTrue(model.feedback.failed)
            assertEquals(name, model.settings?.name)
            assertEquals(5L, model.settings?.version)
        }
    }

    @Test fun `column validation retains invalid drafts and zero WIP explicitly clears the limit`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns")
        model.reload(reads())
        val noWrites = context { _, _ -> throw AssertionError("Invalid columns cannot write") }
        model.saveColumn("Retained", "[]", "", noWrites)
        assertEquals("Open a column editor first.", model.feedback.message)
        for ((name, selected, limit) in listOf(Triple("", "[\"todo\"]", ""), Triple("Retained", "[]", ""),
            Triple("Retained", "[\"missing\"]", ""), Triple("Retained", "[\"working\"]", ""),
            Triple("Retained", "[\"todo\"]", "-1"), Triple("Retained", "[\"todo\"]", "invalid"))) {
            model.editColumn(todo.id)
            model.saveColumn(name, selected, limit, noWrites)
            assertTrue(model.feedback.failed)
            assertEquals(name, model.columnDraft?.name)
            assertEquals(limit, model.columnDraft?.limit)
        }
        model.editColumn(todo.id)
        model.saveColumn("Retained", "[\"todo\"]", "0", context { operation, variables ->
            assertEquals("WorkOpsUpdateBoardColumn", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(JsonNull, input["wipLimit"] ?: JsonNull)
            error("Denied")
        })
        assertEquals("Denied", model.feedback.message)
        assertEquals("0", model.columnDraft?.limit)
        for ((id, direction) in listOf(todo.id to 0, "missing" to 1, todo.id to -1)) {
            model.moveColumn(id, direction, noWrites)
            assertTrue(model.feedback.failed)
        }
    }
    private val todo = WorkOpsBoardColumn("todo-column", "To do", 2, listOf("todo"), 1)
    private val working = WorkOpsBoardColumn("working-column", "Working", 7, listOf("working"), 3)
    private val statuses = Json.parseToJsonElement("""{"workOps":{"statuses":{"all":[{"id":"todo","name":"To do","category":"TODO"},{"id":"working","name":"Working","category":"IN_PROGRESS"},{"id":"done","name":"Done","category":"DONE"}]}}}""")
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun configuration(
        multi: Boolean = true,
        members: List<String> = listOf("project", "hidden", "archived"),
        columns: List<WorkOpsBoardColumn> = listOf(todo, working),
        version: Long = 5,
    ) = buildJsonObject { putJsonObject("workOps") {
        putJsonObject("boards") { putJsonObject("board") {
            put("id", "board"); put("name", "Planning board"); put("type", "SCRUM"); put("swimlaneStrategy", "QUERY"); put("version", version)
            put("projectId", if (multi) JsonNull else JsonPrimitive("project")); put("programId", JsonNull); put("portfolioId", JsonNull)
            putJsonArray("projects") { for (id in members) addJsonObject { put("id", id) } }
            putJsonArray("columns") { for (column in columns) addJsonObject {
                put("id", column.id); put("name", column.name); put("displayOrder", column.order)
                putJsonArray("statusIds") { column.statusIds.forEach { add(JsonPrimitive(it)) } }
                put("wipLimit", column.wipLimit?.let(::JsonPrimitive) ?: JsonNull)
            } }
        } }
        putJsonObject("projects") { putJsonArray("all") {
            for ((id, key, name) in listOf(Triple("project", "PLAN", "Planning project"), Triple("other", "NEXT", "Other project"), Triple("archived", "OLD", "Archived project"))) addJsonObject {
                put("id", id); put("key", key); put("name", name); put("archivedAt", if (id == "archived") JsonPrimitive("2026-01-01T00:00:00Z") else JsonNull)
            }
        } }
    } }
    private fun reads(value: JsonElement = configuration()) = context { operation, _ -> when (operation) {
        "WorkOpsBoardConfiguration" -> value
        "WorkOpsBoardColumnStatuses" -> statuses
        else -> error(operation)
    } }

    @Test fun `settings retain hidden and archived membership and the inspected version after a refresh`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "settings").apply { reload(reads()) }
        assertEquals("scrum · PLAN, OLD", model.subtitle)
        assertFalse(model.subtitle.contains("hidden"))
        assertEquals(listOf("hidden"), model.hiddenMembers)
        assertTrue(model.memberChoices.any { it.id == "archived" }); assertTrue("QUERY" in model.swimlanes)
        model.reload(reads(configuration(version = 9)))
        model.saveSettings("Retained draft", "KANBAN", "QUERY", "[\"other\",\"archived\"]", context { operation, variables ->
            assertEquals("WorkOpsUpdateBoard", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(5L, input.getValue("expectedVersion").jsonPrimitive.long)
            assertEquals(listOf("other"), input.getValue("addProjectIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("project"), input.getValue("removeProjectIds").jsonArray.map { it.jsonPrimitive.content })
            error("Newer board version")
        })
        assertTrue(model.feedback.failed); assertEquals("Retained draft", model.settings?.name)
        assertEquals(setOf("other", "archived", "hidden"), model.settings?.projectIds?.toSet())
        assertEquals(5L, model.settings?.version)
        model.resetSettings(); assertEquals(9L, model.settings?.version)
    }

    @Test fun `a single parent board stays single even though the join table has a project`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "settings").apply { reload(reads(configuration(multi = false, members = listOf("project")))) }
        assertFalse(requireNotNull(model.settings).multiProject)
        assertFalse("PROJECT" in model.swimlanes)
        model.saveSettings("Single board", "SCRUM", "QUERY", "[\"other\"]", context { operation, variables ->
            assertEquals("WorkOpsUpdateBoard", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(JsonNull, input["addProjectIds"] ?: JsonNull); assertEquals(JsonNull, input["removeProjectIds"] ?: JsonNull)
            error("Manage permission denied")
        })
        assertEquals(listOf("project"), model.settings?.projectIds); assertTrue(model.feedback.failed)
    }

    @Test fun `column edits retain the inspected full nonversioned payload and a rejected draft`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns").apply { reload(reads()); editColumn(todo.id) }
        model.reload(reads(configuration(columns = listOf(todo.copy(order = 8), working.copy(order = 10)))))
        model.saveColumn("Edited to do", "[\"todo\",\"todo\"]", "0", context { operation, variables ->
            assertEquals("WorkOpsUpdateBoardColumn", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(2, input.getValue("displayOrder").jsonPrimitive.int)
            assertEquals(listOf("todo"), input.getValue("statusIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(JsonNull, input["wipLimit"] ?: JsonNull); assertFalse("expectedVersion" in input)
            error("Column write denied")
        })
        assertTrue(model.feedback.failed); assertEquals("Edited to do", model.columnDraft?.name)
        assertEquals("0", model.columnDraft?.limit); assertEquals(2, model.columnDraft?.order)
    }

    @Test fun `mapped status choices preserve an existing overlap without offering a new overlap`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns").apply { reload(reads()); editColumn(todo.id) }
        assertEquals(setOf("todo", "done"), model.columnStatusChoices.map { it.id }.toSet())
        model.saveColumn("Draft", "[\"working\"]", "1", context { _, _ -> error("Must not write") })
        assertEquals("Choose at least one available status.", model.feedback.message)
        model.cancelColumn()
        model.reload(reads(configuration(columns = listOf(todo.copy(statusIds = listOf("todo", "working")), working))))
        model.editColumn(todo.id)
        assertTrue(model.columnStatusChoices.any { it.id == "working" })
    }

    @Test fun `new columns use an unused order after gaps and retain every mapped status`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns").apply { reload(reads()); addColumn() }
        assertEquals(8, model.columnDraft?.order)
        model.saveColumn("Done", "[\"done\"]", "", context { operation, variables -> when (operation) {
            "WorkOpsCreateBoardColumn" -> {
                val input = variables.getValue("input").jsonObject
                assertEquals("board", input.getValue("boardId").jsonPrimitive.content); assertEquals(8, input.getValue("displayOrder").jsonPrimitive.int)
                Json.parseToJsonElement("""{"workOps":{"boards":{"addColumn":{"id":"done-column"}}}}""")
            }
            "WorkOpsBoardConfiguration" -> configuration(columns = listOf(todo, working, WorkOpsBoardColumn("done-column", "Done", 8, listOf("done"), null)))
            "WorkOpsBoardColumnStatuses" -> statuses
            else -> error(operation)
        } })
        assertNull(model.columnDraft); assertFalse(model.feedback.failed)
        assertEquals(listOf(2, 7, 8), model.columns.map { it.order })
    }

    @Test fun `reordering preserves column fields and reloads the actual order after a partial write failure`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns").apply { reload(reads()) }
        val writes = mutableListOf<JsonObject>()
        model.moveColumn(todo.id, 1, context { operation, variables -> when (operation) {
            "WorkOpsUpdateBoardColumn" -> {
                val input = variables.getValue("input").jsonObject; writes += input
                if (writes.size == 2) error("Concurrent column order")
                Json.parseToJsonElement("""{"workOps":{"boards":{"updateColumn":{"id":"todo-column"}}}}""")
            }
            "WorkOpsBoardConfiguration" -> configuration(columns = listOf(todo.copy(order = 8), working))
            "WorkOpsBoardColumnStatuses" -> statuses
            else -> error(operation)
        } })
        assertEquals(listOf(8, 2), writes.map { it.getValue("displayOrder").jsonPrimitive.int })
        assertEquals(listOf(1, 3), writes.map { it.getValue("wipLimit").jsonPrimitive.int })
        assertEquals(listOf("todo", "working"), writes.map { it.getValue("statusIds").jsonArray.single().jsonPrimitive.content })
        assertTrue(model.feedback.failed); assertTrue(model.columnsLoaded)
        assertEquals(listOf(working.id, todo.id), model.columns.map { it.id })
        assertEquals(listOf(7, 8), model.columns.map { it.order })
    }

    @Test fun `a reorder failure with an unavailable reload hides stale columns and preserves the original error`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns").apply { reload(reads()) }
        model.moveColumn(todo.id, 1, context { operation, _ ->
            if (operation == "WorkOpsUpdateBoardColumn") error("Order write failed") else error("Reload unavailable")
        })
        assertEquals("Order write failed", model.feedback.message); assertFalse(model.columnsLoaded)
        model.retry(reads())
        assertFailsWith<CancellationException> { model.moveColumn(todo.id, 1, context { _, _ -> throw CancellationException("Left board") }) }
    }

    @Test fun `column deletion requires confirmation and retains the chosen identity after denial`() = runTest {
        val model = WorkOpsBoardConfigurationModel("board", "columns").apply { reload(reads()); beginDelete(todo.id) }
        model.deleteColumn(false, context { _, _ -> error("Must not write") })
        assertEquals("Confirm deleting this column.", model.feedback.message)
        model.deleteColumn(true, context { operation, variables ->
            assertEquals("WorkOpsDeleteBoardColumn", operation); assertEquals(todo.id, variables.getValue("id").jsonPrimitive.content)
            error("Manage denied")
        })
        assertEquals(todo, model.deleteTarget); assertTrue(model.feedback.failed)
    }
}
