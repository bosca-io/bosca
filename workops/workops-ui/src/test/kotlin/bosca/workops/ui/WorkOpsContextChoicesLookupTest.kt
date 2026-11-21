package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.workops.model.bql.BqlExpr
import bosca.workops.model.bql.BqlParser
import bosca.workops.model.bql.BqlValidator
import bosca.workops.model.bql.BqlValue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsContextChoicesLookupTest {
    private fun client(handler: suspend (String, JsonObject) -> JsonElement) = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    }

    @Test fun `unavailable searches and unnamed references keep explicit empty and fallback labels`() = runTest {
        val choices = WorkOpsContextChoicesLookup()
        for (kind in listOf("METADATA", "COLLECTION", "PROFILE")) {
            assertEquals(mapOf("options" to "[]"), choices.search(client { _, _ ->
                Json.parseToJsonElement("""{"search":{"search":null}}""")
            }, kind, "Reference", 0))
        }
        for ((kind, label) in listOf("COLLECTION" to "Collection", "PROFILE" to "Profile")) {
            val result = choices.search(client { _, _ -> Json.parseToJsonElement("""{"search":{"search":{"documents":[
              {"${kind.lowercase()}":{"id":"reference","name":""}},{"${kind.lowercase()}":{"id":"reference","name":""}}]}}}""")
            }, kind, "Reference", 0)
            val rows = Json.parseToJsonElement(result.getValue("options")).jsonArray
            assertEquals(1, rows.size)
            assertEquals(label, rows.single().jsonObject.getValue("label").jsonPrimitive.content)
        }
        assertFailsWith<IllegalArgumentException> { choices.search(client { _, _ -> error("Unexpected") }, "TASK", "Reference", -1) }
        assertFailsWith<IllegalStateException> { choices.search(client { _, _ -> error("Unexpected") }, "UNKNOWN", "Reference", 0) }
        val specs = choices.search(client { operation, _ ->
            assertEquals("WorkOpsSpecContentChoices", operation)
            Json.parseToJsonElement("""{"search":{"search":null}}""")
        }, "SPEC", "Reference", 0)
        assertEquals(mapOf("options" to "[]"), specs)
    }

    @Test fun `content choices preserve raw sentinel pages across inaccessible entries`() = runTest {
        val choices = WorkOpsContextChoicesLookup()
        for (kind in listOf("METADATA", "COLLECTION", "PROFILE")) {
            val result = choices.search(client { operation, variables ->
                assertEquals("WorkOpsContextItems", operation)
                assertEquals(25, variables.getValue("offset").jsonPrimitive.int)
                assertEquals("_type = \"${kind.lowercase()}\"", variables.getValue("filter").jsonArray.single().jsonPrimitive.content)
                buildJsonObject { putJsonObject("search") { putJsonObject("search") { putJsonArray("documents") {
                    repeat(26) { index -> addJsonObject {
                        if (index == 1) putJsonObject(kind.lowercase()) { put("id", "visible"); put("name", "Visible reference") }
                    } }
                } } } }
            }, kind, "Visible", 25)
            assertEquals("0", result["previousOffset"]); assertEquals("50", result["nextOffset"])
            assertEquals("Visible reference", Json.parseToJsonElement(requireNotNull(result["options"])).jsonArray.single().jsonObject.getValue("label").jsonPrimitive.content)
        }
    }

    @Test fun `task queries quote authored search text and project choices filter before offset paging`() = runTest {
        val choices = WorkOpsContextChoicesLookup()
        val authored = "report\" OR status = Done"
        choices.search(client { operation, variables ->
            assertEquals("WorkOpsContextTasks", operation)
            assertEquals("summary ~ \"report\\\" OR status = Done\" ORDER BY modified DESC", variables.getValue("source").jsonPrimitive.content)
            val parsed = BqlParser(variables.getValue("source").jsonPrimitive.content).parse()
            assertTrue(parsed.isSuccess, parsed.errors.toString())
            val query = requireNotNull(parsed.query)
            assertTrue(BqlValidator().validate(query).isEmpty())
            assertEquals(BqlValue.Literal(JsonPrimitive(authored)), assertIs<BqlExpr.Compare>(query.where).value)
            Json.parseToJsonElement("""{"workOps":{"savedFilters":{"searchTasks":{"rows":[]}}}}""")
        }, "TASK", authored, 0)
        val result = choices.search(client { operation, _ ->
            assertEquals("WorkOpsSpecCreateChoices", operation)
            buildJsonObject { putJsonObject("workOps") { putJsonObject("projects") { putJsonArray("all") {
                addJsonObject { put("id", "unmatched"); put("key", "OTHER"); put("name", "Other") }
                repeat(51) { index -> addJsonObject { put("id", "project-$index"); put("key", "P$index"); put("name", "Search project $index") } }
            } } } }
        }, "PROJECT", "Search", 25)
        val options = Json.parseToJsonElement(requireNotNull(result["options"])).jsonArray
        assertEquals(25, options.size); assertEquals("project-25", options.first().jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("50", result["nextOffset"])
        assertFailsWith<IllegalArgumentException> { choices.search(client { _, _ -> error("Unexpected call") }, "TASK", "x", 0) }
    }
}
