package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsProfileLookupTest {
    @Test fun `profile choices retain authorized search results and offset paging`() = runTest {
        var requests = 0
        val gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                requests++
                assertEquals("WorkOpsProfileChoices", operationName)
                assertEquals("owner", variables?.get("query")?.jsonPrimitive?.content)
                assertEquals(20, variables?.get("offset")?.jsonPrimitive?.int)
                assertTrue("Admin Search Index" in query && "profile" in query)
                return Json.parseToJsonElement("""{"search":{"search":{"documents":[{"profile":null},{"profile":{"id":"profile","name":"Owner","slug":"owner"}},{"profile":{"id":"profile","name":"Owner","slug":"owner"}}]}}}""")
            }
        }
        val lookup = WorkOpsProfileLookup()
        assertFailsWith<IllegalArgumentException> { lookup.search(gql, "x", 0) }
        assertFailsWith<IllegalArgumentException> { lookup.search(gql, "owner", -1) }
        assertEquals(0, requests)
        assertEquals(listOf(mapOf("id" to "profile", "label" to "Owner", "handle" to "owner")), lookup.search(gql, " owner ", 20))
        assertEquals(1, requests)
    }
}
