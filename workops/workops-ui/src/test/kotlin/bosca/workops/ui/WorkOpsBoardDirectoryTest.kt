package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsBoardDirectoryTest {
    @Test fun `new board validation retains drafts and rejects invalid scope swimlanes and archived projects`() = runTest {
        val choices = listOf(WorkOpsPlanningRow(id = "project"), WorkOpsPlanningRow(id = "other"), WorkOpsPlanningRow(id = "archived", archived = true))
        val model = WorkOpsBoardDirectoryModel("project", projects = choices)
        val noWrites = context { _, _ -> throw AssertionError("Invalid board draft cannot write") }
        model.create("Retained", "SCRUM", "single", "NONE", "[]", noWrites)
        assertEquals("Open the new board form first.", model.feedback.message)
        for ((name, type, scope, swimlane, members) in listOf(
            listOf("", "SCRUM", "single", "NONE", "[]"), listOf("Retained", "UNKNOWN", "single", "NONE", "[]"),
            listOf("Retained", "SCRUM", "unknown", "NONE", "[]"), listOf("Retained", "SCRUM", "single", "PROJECT", "[]"),
            listOf("Retained", "SCRUM", "single", "UNKNOWN", "[]"), listOf("Retained", "SCRUM", "multi", "PROJECT", "[]"),
            listOf("Retained", "SCRUM", "multi", "PROJECT", "[\"project\",\"missing\"]"),
            listOf("Retained", "SCRUM", "multi", "PROJECT", "[\"project\",\"archived\"]"))) {
            model.open()
            model.create(name, type, scope, swimlane, members, noWrites)
            assertTrue(model.feedback.failed)
            assertEquals(name, model.nameDraft)
            assertTrue(model.creating)
        }
    }
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private val projects = listOf(
        WorkOpsPlanningRow(id = "project", key = "PLAN", name = "Planning project"),
        WorkOpsPlanningRow(id = "other", key = "NEXT", name = "Other project"),
        WorkOpsPlanningRow(id = "archived", key = "OLD", name = "Archived project", archived = true),
    )

    @Test fun `directory combines distinct project boards and labels only authorized project names`() = runTest {
        val model = WorkOpsBoardDirectoryModel()
        model.reload(context { operation, variables -> when (operation) {
            "WorkOpsBoardProjects" -> json("""{"workOps":{"projects":{"all":[{"id":"project","key":"PLAN","name":"Planning project","archivedAt":null}]}}}""")
            "WorkOpsBoardsForProject" -> {
                assertEquals("project", variables.getValue("project").jsonPrimitive.content)
                val board = """{"id":"board","name":"Shared board","type":"SCRUM","projectId":null,"programId":null,"portfolioId":null,"projects":[{"id":"project"},{"id":"hidden-project"}]}"""
                json("""{"workOps":{"boards":{"byProject":[$board],"byProjects":[$board]}}}""")
            }
            else -> error(operation)
        } }, chooseFirst = true)
        assertEquals("project", model.projectId); assertEquals("board", model.boardId)
        assertEquals(1, model.boards.size); assertEquals("Shared board (PLAN)", model.label(model.boards.single()))
        assertFalse(model.label(model.boards.single()).contains("hidden-project"))
    }

    @Test fun `single board creation sends the selected project and retains a rejected draft`() = runTest {
        val model = WorkOpsBoardDirectoryModel("project", projects = projects).apply { open() }
        model.create("Board draft", "SCRUM", "single", "PRIORITY", "[]", context { operation, variables ->
            assertEquals("WorkOpsCreateBoard", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals("project", input.getValue("projectId").jsonPrimitive.content)
            assertEquals(JsonNull, input["projectIds"] ?: JsonNull)
            assertEquals("SCRUM", input.getValue("type").jsonPrimitive.content)
            assertEquals("PRIORITY", input.getValue("swimlaneStrategy").jsonPrimitive.content)
            error("Manage permission denied")
        })
        assertTrue(model.creating); assertTrue(model.feedback.failed)
        assertEquals("Board draft", model.nameDraft); assertEquals("Manage permission denied", model.feedback.message)
    }

    @Test fun `multi board creation validates available scope and preserves the full selection`() = runTest {
        val model = WorkOpsBoardDirectoryModel("project", projects = projects).apply { open() }
        for (ids in listOf("[\"project\"]", "[\"project\",\"archived\"]", "[\"project\",\"hidden\"]")) {
            model.create("Cross-project draft", "KANBAN", "multi", "PROJECT", ids, context { _, _ -> error("Must not write") })
            assertEquals("Choose at least two available active projects.", model.feedback.message)
            assertTrue(model.creating)
        }
        model.create("Cross-project draft", "KANBAN", "multi", "PROJECT", "[\"project\",\"other\",\"project\"]", context { operation, variables ->
            assertEquals("WorkOpsCreateBoard", operation)
            val input = variables.getValue("input").jsonObject
            assertEquals(JsonNull, input["projectId"] ?: JsonNull)
            assertEquals(listOf("project", "other"), input.getValue("projectIds").jsonArray.map { it.jsonPrimitive.content })
            error("Create rejected")
        })
        assertEquals(listOf("project", "other"), model.projectDraft)
        assertEquals("Cross-project draft", model.nameDraft)
        assertTrue(model.creating)
    }

    @Test fun `a created board remains directly reachable when refreshing choices fails`() = runTest {
        val model = WorkOpsBoardDirectoryModel("project", projects = projects).apply { open() }
        model.create("Created board", "KANBAN", "single", "NONE", "[]", context { operation, _ ->
            if (operation == "WorkOpsCreateBoard") json("""{"workOps":{"boards":{"create":{"id":"created"}}}}""")
            else error("Choices unavailable")
        })
        assertFalse(model.creating); assertTrue(model.feedback.failed)
        assertEquals("/workops/boards?projectId=project&boardId=created", model.createdHref)
    }

    @Test fun `a cross-project board result opens within its new scope when the header project is excluded`() = runTest {
        val model = WorkOpsBoardDirectoryModel("archived", projects = projects).apply { open() }
        model.create("Cross-project board", "SCRUM", "multi", "PROJECT", "[\"project\",\"other\"]", context { operation, _ ->
            if (operation == "WorkOpsCreateBoard") json("""{"workOps":{"boards":{"create":{"id":"created"}}}}""")
            else error("Choices unavailable")
        })
        assertFalse(model.creating)
        assertEquals("/workops/boards?projectId=project&boardId=created", model.createdHref)
    }

    @Test fun `archived projects and project swimlanes cannot be silently created as a single board`() = runTest {
        val model = WorkOpsBoardDirectoryModel("archived", projects = projects).apply { open() }
        model.create("Draft", "KANBAN", "single", "NONE", "[]", context { _, _ -> error("Must not write") })
        assertEquals("Select an active project first.", model.feedback.message)
        model.create("Draft", "KANBAN", "single", "PROJECT", "[]", context { _, _ -> error("Must not write") })
        assertEquals("Project swimlanes require a multi-project board.", model.feedback.message)
        assertFailsWith<CancellationException> { model.retry(context { _, _ -> throw CancellationException("Left board") }) }
    }
}
