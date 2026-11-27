package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecSearchTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    private val valid = Json.parseToJsonElement("""{"workOps":{"savedFilters":{"validateSpecBql":{"errors":[]}}}}""")
    private val filters = Json.parseToJsonElement("""{"workOps":{"savedFilters":{"mine":[{"id":"filter","name":"Project work","bqlSource":"project = UI"}]}}}""")

    @Test fun `specification results preserve private names child counts filters and an offset sentinel`() = runTest {
        val page = WorkOpsSpecSearchModel("owner = currentUser()", 25)
        page.load(context { operation, variables -> when (operation) {
            "WorkOpsValidateSpecBql" -> valid
            "WorkOpsTaskFilters" -> filters
            "WorkOpsSpecSearch" -> {
                assertEquals(25L, variables.getValue("offset").jsonPrimitive.long)
                buildJsonObject { putJsonObject("workOps") { putJsonObject("savedFilters") { putJsonObject("searchSpecs") {
                    putJsonArray("freeTextTerms") { add(JsonPrimitive("term")) }
                    putJsonArray("rows") { repeat(26) { index -> addJsonObject {
                        put("id", "spec-$index"); put("metadataId", "metadata-$index")
                        putJsonObject("metadata") { put("name", "TEAM-SPEC-42") }
                        putJsonObject("status") { put("name", "Done"); put("category", "DONE") }
                        put("ownerProfileId", "private-owner"); put("owner", JsonNull); put("project", JsonNull)
                        put("childCount", 3); put("childDoneCount", 2); put("requirementCount", 8)
                        put("gitRepositoryId", "repository"); put("modifiedAt", "2026-11-14T10:00:00Z")
                    } } }
                } } } }
            }
            else -> error(operation)
        } })
        assertFalse(page.failed); assertTrue(page.valid); assertEquals(25, page.rows.size); assertTrue(page.hasNext)
        assertEquals("Specification", page.rows.first().title)
        assertEquals("Profile unavailable", page.rows.first().owner)
        assertEquals(3, page.rows.first().childCount); assertEquals(2, page.rows.first().childDoneCount)
        assertEquals(8L, page.rows.first().requirementCount); assertTrue(page.rows.first().gitLinked)
        assertEquals("/workops/specs?bql=project%20%3D%20UI", page.filters.single().href)
        assertTrue(page.nextHref.endsWith("&offset=50")); assertTrue(page.previousHref.endsWith("&offset=0"))
        assertEquals(listOf("term"), page.freeTextTerms)
    }

    @Test fun `invalid specification queries retain diagnostic ranges without executing search`() = runTest {
        val operations = mutableListOf<String>()
        val page = WorkOpsSpecSearchModel("owner =")
        page.load(context { operation, _ ->
            operations += operation
            when (operation) {
                "WorkOpsValidateSpecBql" -> Json.parseToJsonElement("""{"workOps":{"savedFilters":{"validateSpecBql":{"errors":[{"message":"Expected owner","start":7,"end":7,"hint":"Choose a profile"}]}}}}""")
                "WorkOpsTaskFilters" -> filters
                else -> error(operation)
            }
        })
        assertFalse(page.valid); assertFalse(page.failed); assertEquals("owner =", page.source)
        assertEquals(listOf("WorkOpsValidateSpecBql", "WorkOpsTaskFilters"), operations)
        assertContains(page.validationMessage, "7–7"); assertContains(page.validationMessage, "Choose a profile")
    }

    @Test fun `failed specification reads remain distinct from empty results and cancellation propagates`() = runTest {
        val page = WorkOpsSpecSearchModel()
        page.load(context { _, _ -> error("Specification service unavailable") })
        assertTrue(page.failed); assertFalse(page.valid)
        assertEquals(listOf("Specification service unavailable"), page.errors)
        assertFailsWith<CancellationException> { page.load(context { _, _ -> throw CancellationException("closed") }) }
    }
}
