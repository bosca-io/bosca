package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsBqlLookupTest {
    @Test fun `specification validation calls its owning catalog operation`() = runTest {
        val gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                assertEquals("WorkOpsValidateSpecBql", operationName)
                assertEquals("owner = currentUser()", variables?.get("source")?.jsonPrimitive?.content)
                return Json.parseToJsonElement("""{"workOps":{"savedFilters":{"validateSpecBql":{"errors":[]}}}}""")
            }
        }
        assertTrue(WorkOpsBqlLookup().validateSpec(gql, "owner = currentUser()").isEmpty())
    }
    @Test fun `live validation preserves structured diagnostics without running task search`() = runTest {
        val lookup = WorkOpsBqlLookup()
        val gql = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                assertEquals("WorkOpsValidateBql", operationName)
                assertEquals("status =", variables?.get("source")?.jsonPrimitive?.content)
                return Json.parseToJsonElement("""{"workOps":{"savedFilters":{"validateBql":{"errors":[{"message":"Expected value","start":8,"end":8,"hint":"Choose a status"}]}}}}""")
            }
        }
        assertEquals(listOf(mapOf("message" to "Expected value", "start" to "8", "end" to "8", "hint" to "Choose a status",
            "description" to "Expected value (query range 8–8). Choose a status")), lookup.validate(gql, "status ="))
    }

    @Test fun `cancellation propagates and assignee labels retain assignment meaning`() = runTest {
        val cancelled = object : GraphQLClient {
            override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = throw CancellationException("stopped")
        }
        assertFailsWith<CancellationException> { WorkOpsBqlLookup().validate(cancelled, "") }
        assertEquals("Unassigned", workOpsAssigneeLabel(null, null))
        assertEquals("Profile unavailable", workOpsAssigneeLabel("private-profile", null))
        assertEquals("Assignee", workOpsAssigneeLabel("profile", "Assignee"))
    }
}
