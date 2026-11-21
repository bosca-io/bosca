package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsFiltersTest {
    private val row = WorkOpsSavedFilterRow("filter", "My filter", "Description", "project = TEST", "2026-11-13T12:00:00Z", 7)
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private fun json(source: String) = Json.parseToJsonElement(source)
    private val valid = json("""{"workOps":{"savedFilters":{"validateBql":{"errors":[]}}}}""")

    @Test fun `invalid saved filter retains its complete query diagnostic without mutating`() = runTest {
        val model = WorkOpsFiltersModel().apply { create() }
        model.save("Draft name", "Draft description", "status =", context { operation, _ ->
            assertEquals("WorkOpsValidateBql", operation)
            json("""{"workOps":{"savedFilters":{"validateBql":{"errors":[{"message":"Expected value","start":8,"end":8,"hint":"Choose a status"}]}}}}""")
        })
        assertTrue(model.editing)
        assertEquals("status =", model.draft.source)
        assertEquals("Draft name", model.draft.name)
        assertEquals(listOf(WorkOpsBqlDiagnostic("Expected value", 8, 8, "Choose a status")), model.diagnostics)
    }

    @Test fun `a refreshed list cannot replace the version captured by an open edit`() = runTest {
        val model = WorkOpsFiltersModel(rows = listOf(row))
        model.edit(row.id)
        model.rows = listOf(row.copy(version = 8))
        model.save("Edited name", "Edited description", "project = TEST ORDER BY modified DESC", context { operation, variables -> when (operation) {
            "WorkOpsValidateBql" -> valid
            "WorkOpsUpdateSavedFilter" -> {
                assertEquals(7L, variables.getValue("expectedVersion").jsonPrimitive.long)
                assertEquals("Edited name", variables.getValue("input").jsonObject.getValue("name").jsonPrimitive.content)
                error("A newer filter was saved")
            }
            else -> error(operation)
        } })
        assertTrue(model.editing)
        assertTrue(model.feedback.failed)
        assertEquals("Edited name", model.draft.name)
        assertEquals(7L, model.draft.version)
        assertEquals(8L, model.rows.single().version)
    }

    @Test fun `saved filter pages retain offset and escape queries in run destinations`() = runTest {
        val model = WorkOpsFiltersModel(offset = 50)
        model.reload(context { operation, variables ->
            assertEquals("WorkOpsSavedFiltersPage", operation)
            assertEquals(50L, variables.getValue("offset").jsonPrimitive.long)
            buildJsonObject { putJsonObject("workOps") { putJsonObject("savedFilters") { putJsonArray("mine") {
                repeat(51) { add(buildJsonObject {
                    put("id", "filter-$it"); put("name", "Filter $it"); put("description", JsonNull)
                    put("bqlSource", "summary ~ \"a&b\""); put("modifiedAt", row.modified); put("version", 1)
                }) }
            } } } }
        })
        assertEquals(50, model.rows.size)
        assertEquals(2L, model.page)
        assertEquals("/workops/filters?offset=0", model.previousHref)
        assertEquals("/workops/filters?offset=100", model.nextHref)
        assertEquals("/workops/tasks?bql=summary%20%7E%20%22a%26b%22", model.rows.first().href)
    }

    @Test fun `deletion requires confirmation and failed previews stay distinct from empty results`() = runTest {
        val model = WorkOpsFiltersModel(rows = listOf(row))
        val noRequest = context { operation, _ -> error("Unexpected $operation") }
        model.beginDelete(row.id)
        model.delete(false, noRequest)
        assertTrue(model.feedback.failed)
        assertEquals(row.id, model.deletingId)
        model.preview(row.id, context { operation, _ -> when (operation) {
            "WorkOpsValidateBql" -> valid
            else -> error("Preview unavailable")
        } })
        assertTrue(model.feedback.failed)
        assertEquals(row.id, model.previewId)
        assertTrue(model.previewRows.isEmpty())
        assertEquals("Preview unavailable", model.feedback.message)
    }
}
