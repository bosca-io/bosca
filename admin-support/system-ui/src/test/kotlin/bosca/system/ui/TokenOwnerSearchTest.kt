package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class TokenOwnerSearchTest {
    private fun client(admin: Boolean, search: suspend (JsonObject) -> JsonElement) = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = when (operationName) {
            "SystemAccess" -> Json.parseToJsonElement("""{"security":{"principals":{"current":{"id":"caller","groups":${if (admin) "[{\"name\":\"administrators\"}]" else "[]"}}}}}""")
            "SystemTokenOwners" -> search(requireNotNull(variables))
            else -> error("Unexpected operation: $operationName")
        }
    }

    @Test fun `owner choices use principal identities and skip profiles without accounts`() = runTest {
        val choices = TokenOwnerSearch().search(client(true) { variables ->
            assertEquals("Reader", variables.getValue("query").jsonPrimitive.content)
            Json.parseToJsonElement("""{"search":{"search":{"documents":[{"profile":{"id":"profile-one","name":"Reader","principal":{"id":"principal-one"}}},{"profile":{"id":"profile-two","name":"Other profile","principal":{"id":"principal-one"}}},{"profile":{"id":"profile-three","name":"No account","principal":null}}]}}}""")
        }, " Reader ")
        assertEquals(listOf(mapOf("id" to "principal-one", "label" to "Reader")), choices)
    }

    @Test fun `owner search enforces administrator access and propagates cancellation`() = runTest {
        assertFailsWith<IllegalArgumentException> { TokenOwnerSearch().search(client(false) { error("Must not search") }, "Reader") }
        assertFailsWith<IllegalArgumentException> { TokenOwnerSearch().search(client(true) { error("Must not search") }, "R") }
        assertFailsWith<CancellationException> { TokenOwnerSearch().search(client(true) { throw CancellationException() }, "Reader") }
    }
}
