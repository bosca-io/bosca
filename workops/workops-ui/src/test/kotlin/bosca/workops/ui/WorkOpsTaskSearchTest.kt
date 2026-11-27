package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsTaskSearchTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })

    private fun json(value: String) = Json.parseToJsonElement(value)
    private val valid = json("""{"workOps":{"savedFilters":{"validateBql":{"errors":[]}}}}""")
    private val filters = json("""{"workOps":{"savedFilters":{"mine":[{"id":"filter","name":"Assigned work","bqlSource":"assignee = me()"}]}}}""")

    private fun search(size: Int) = buildJsonObject {
        putJsonObject("workOps") { putJsonObject("savedFilters") { putJsonObject("searchTasks") {
            putJsonArray("rows") {
                repeat(size) { index -> add(buildJsonObject {
                    put("id", "task-$index"); put("key", "TEST-$index"); put("summary", "Task $index")
                    putJsonObject("status") { put("id", "status"); put("name", "To Do"); put("category", "TODO") }
                    putJsonObject("priority") { put("id", "priority"); put("name", "Medium"); put("displayOrder", 2); put("colorHex", "#91a6f6") }
                    putJsonObject("taskType") { put("id", "type"); put("name", "Task") }
                    put("assigneeProfileId", if (index == 1) JsonPrimitive("private-profile") else JsonNull)
                    put("assignee", JsonNull); put("dueDate", JsonNull)
                }) }
            }
            putJsonArray("freeTextTerms") { add("quoted text") }
        } } }
    }

    @Test fun `search hides generated requirement summaries while preserving authored names`() = runTest {
        val model = WorkOpsTaskSearchModel("ORDER BY modified DESC")
        model.load(context { operation, _ -> when (operation) {
            "WorkOpsValidateBql" -> valid
            "WorkOpsTaskFilters" -> filters
            "WorkOpsTaskSearch" -> json(search(2).toString().replace("Task 0", "REQ TEST-REQ-27").replace("Task 1", "Task"))
            else -> error(operation)
        } })
        assertFalse(model.failed)
        assertEquals(listOf("Requirement", "Task"), model.rows.map { it.summary })
    }

    @Test fun `offset search keeps a 25 row window and escapes the full query in both page links`() = runTest {
        val source = "summary ~ \"a&b + c\" ORDER BY modified DESC"
        val model = WorkOpsTaskSearchModel(source, 25)
        model.load(context { operation, variables ->
            when (operation) {
                "WorkOpsValidateBql" -> { assertEquals(source, variables.getValue("source").jsonPrimitive.content); valid }
                "WorkOpsTaskFilters" -> filters
                "WorkOpsTaskSearch" -> {
                    assertEquals(source, variables.getValue("source").jsonPrimitive.content)
                    assertEquals(25L, variables.getValue("offset").jsonPrimitive.long)
                    search(26)
                }
                else -> error(operation)
            }
        })
        assertFalse(model.failed)
        assertEquals(25, model.rows.size)
        assertEquals(2L, model.page)
        assertTrue(model.hasNext)
        assertEquals("/workops/tasks?bql=${workOpsEncode(source)}&offset=0", model.previousHref)
        assertEquals("/workops/tasks?bql=${workOpsEncode(source)}&offset=50", model.nextHref)
        assertTrue("a%26b%20%2B%20c" in model.nextHref)
        assertEquals(listOf("quoted text"), model.freeTextTerms)
        assertEquals("/workops/tasks/task-0", model.rows.first().href)
        assertEquals("", model.rows.first().assignee)
        assertEquals("Unassigned", model.rows.first().assigneeLabel)
        assertEquals("Profile unavailable", model.rows[1].assigneeLabel)
        assertEquals("private-profile", model.rows[1].assigneeProfileId)
        assertEquals("/workops/tasks?bql=assignee%20%3D%20me%28%29", model.filters.single().href)
    }

    @Test fun `invalid BQL retains the draft and diagnostic without executing task SQL`() = runTest {
        val model = WorkOpsTaskSearchModel("status =")
        model.load(context { operation, _ ->
            when (operation) {
                "WorkOpsValidateBql" -> json("""{"workOps":{"savedFilters":{"validateBql":{"errors":[{"message":"Expected value","start":8,"end":8,"hint":"Choose a status"}]}}}}""")
                "WorkOpsTaskFilters" -> filters
                else -> error("Invalid BQL must not execute $operation")
            }
        })
        assertEquals("status =", model.source)
        assertEquals(listOf(WorkOpsBqlDiagnostic("Expected value", 8, 8, "Choose a status")), model.diagnostics)
        assertTrue(model.errors.isEmpty())
        assertFalse(model.valid)
        assertFalse(model.failed)
        assertTrue(model.rows.isEmpty())
    }

    @Test fun `transport failures remain visible and cancellation propagates`() = runTest {
        val model = WorkOpsTaskSearchModel("project = TEST")
        model.load(context { _, _ -> error("Search unavailable") })
        assertTrue(model.failed)
        assertEquals(listOf("Search unavailable"), model.errors)
        assertEquals("/workops/tasks?bql=project%20%3D%20TEST&offset=0", model.retryHref)
        assertFailsWith<CancellationException> {
            WorkOpsTaskSearchModel().load(context { _, _ -> throw CancellationException("stopped") })
        }
    }

    @Test fun `request parsing preserves explicit empty BQL and rejects malformed negative offsets`() = runTest {
        val gql = context { operation, _ -> when (operation) {
            "WorkOpsValidateBql" -> valid
            "WorkOpsTaskFilters" -> filters
            "WorkOpsTaskSearch" -> search(0)
            else -> error(operation)
        } }.gql
        for (offset in listOf("-25", "invalid")) {
            val ctx = RenderContext(gql = gql, query = mapOf("bql" to "", "offset" to offset))
            val model = withRenderContext(ctx) { workOpsTaskSearch() }
            assertEquals("", model.source)
            assertEquals(0L, model.offset)
            assertEquals("", model.previousHref)
            assertEquals("", model.nextHref)
        }
    }
}
