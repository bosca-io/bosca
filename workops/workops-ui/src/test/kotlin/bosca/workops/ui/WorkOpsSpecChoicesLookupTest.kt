package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsSpecChoicesLookupTest {
    @Test fun `spec choices omit inaccessible metadata and retain labels without a project`() = runTest {
        val lookup = WorkOpsSpecChoicesLookup()
        var hideMetadata = true
        val client = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement =
                if (operationName == "WorkOpsSpecContentChoices") Json.parseToJsonElement("""{"search":{"search":{"documents":[{"metadata":{"id":"document"}}]}}}""")
                else Json.parseToJsonElement("""{"workOps":{"specs":{"specByMetadataId":{"id":"spec","metadataId":"document",
                  "metadata":${if (hideMetadata) "null" else "{\"name\":\"Authored title\"}"},"project":null}}}}""")
        }
        assertEquals(mapOf("options" to "[]"), lookup.search(client, "Title", 0))
        hideMetadata = false
        val options = Json.parseToJsonElement(lookup.search(client, "Title", 0).getValue("options")).jsonArray
        assertEquals("Authored title", options.single().jsonObject.getValue("label").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { lookup.search(client, "Title", -1) }
        assertFailsWith<IllegalArgumentException> { lookup.search(client, "x", 0) }
    }

    @Test fun `title choices retain raw offset pages across inaccessible and non specification content`() = runTest {
        val visited = mutableListOf<String>()
        val gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = when (operationName) {
                "WorkOpsSpecContentChoices" -> {
                    assertEquals("Parent", variables?.get("query")?.jsonPrimitive?.content)
                    assertEquals(25, variables?.get("offset")?.jsonPrimitive?.int)
                    buildJsonObject { putJsonObject("search") { putJsonObject("search") { putJsonArray("documents") {
                        repeat(26) { index -> addJsonObject {
                            if (index == 0) put("metadata", JsonNull) else putJsonObject("metadata") { put("id", "metadata-$index") }
                        } }
                    } } } }
                }
                "WorkOpsSpecChoiceByMetadata" -> {
                    val id = requireNotNull(variables)["id"]?.jsonPrimitive?.content.orEmpty(); visited += id
                    if (id == "metadata-1") Json.parseToJsonElement("""{"workOps":{"specs":{"specByMetadataId":{"id":"spec","metadataId":"metadata-1","metadata":{"name":"TEAM-SPEC-42"},"project":{"name":"Project"}}}}}""")
                    else Json.parseToJsonElement("""{"workOps":{"specs":{"specByMetadataId":null}}}""")
                }
                else -> error(operationName.orEmpty())
            }
        }
        val result = WorkOpsSpecChoicesLookup().search(gql, " Parent ", 25)
        assertEquals("0", result["previousOffset"]); assertEquals("50", result["nextOffset"])
        val options = Json.parseToJsonElement(requireNotNull(result["options"])).jsonArray
        assertEquals(1, options.size); assertEquals("spec", options[0].jsonObject["value"]?.jsonPrimitive?.content)
        assertEquals("Specification · Project", options[0].jsonObject["label"]?.jsonPrimitive?.content)
        assertFalse("metadata-0" in visited); assertFalse("metadata-25" in visited)
    }
}
