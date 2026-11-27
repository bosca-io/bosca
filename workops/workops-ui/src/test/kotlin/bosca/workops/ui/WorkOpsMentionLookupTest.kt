package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsMentionLookupTest {
    private fun client(handler: (String, JsonObject) -> JsonElement) = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            handler(requireNotNull(operationName), requireNotNull(variables))
    }

    @Test fun `unavailable searches stay empty and unnamed references retain explicit human fallbacks`() = runTest {
        val lookup = WorkOpsMentionLookup()
        val missing = client { _, _ -> Json.parseToJsonElement("""{"search":{"search":null}}""") }
        assertEquals(emptyList(), lookup.profiles(missing, ""))
        assertEquals(emptyList(), lookup.entities(missing, "Reference"))
        assertEquals(emptyList(), WorkOpsProfileLookup().search(missing, "Person", 0))
        assertFailsWith<IllegalArgumentException> { WorkOpsProfileLookup().search(missing, "Person", -1) }
        val rows = lookup.entities(client { _, _ -> Json.parseToJsonElement("""{"search":{"search":{"documents":[
          {"metadata":null,"collection":{"id":"collection","name":""},"profile":null},
          {"metadata":null,"collection":null,"profile":{"id":"profile","name":"","slug":null}}
        ]}}}""") }, "Reference")
        assertEquals(listOf("Untitled collection", "Unnamed profile"), rows.map { it.getValue("label") })
    }

    @Test fun `comment mentions search an empty prefix and exclude profiles without usable handles`() = runTest {
        var requests = 0
        val gql = client { operation, variables ->
            requests++
            assertEquals("WorkOpsProfileChoices", operation)
            assertTrue(variables.getValue("query").jsonPrimitive.content in listOf("", "JAM"))
            assertEquals(0, variables.getValue("offset").jsonPrimitive.int)
            Json.parseToJsonElement("""{"search":{"search":{"documents":[
                {"profile":null},
                {"profile":{"id":"named","name":"Jamie","slug":"jamie"}},
                {"profile":{"id":"named","name":"Jamie","slug":"jamie"}},
                {"profile":{"id":"handle","name":"","slug":"avery"}},
                {"profile":{"id":"private","name":"Private","slug":null}},
                {"profile":{"id":"invalid","name":"Invalid","slug":"not a handle"}}
            ]}}}""")
        }
        val lookup = WorkOpsMentionLookup()
        assertFailsWith<IllegalArgumentException> { lookup.profiles(gql, "two words") }
        assertEquals(0, requests)
        assertEquals(listOf(mapOf("id" to "named", "label" to "Jamie", "handle" to "jamie"),
            mapOf("id" to "handle", "label" to "avery", "handle" to "avery")), lookup.profiles(gql, ""))
        assertEquals(1, requests)
        assertEquals(listOf(mapOf("id" to "named", "label" to "Jamie", "handle" to "jamie")), lookup.profiles(gql, "JAM"))
        assertEquals(2, requests)
    }

    @Test fun `document mentions retain authorized entity types and supply human labels rather than identity fallbacks`() = runTest {
        val gql = client { operation, variables ->
            assertEquals("WorkOpsMentionEntities", operation)
            assertEquals("launch", variables.getValue("query").jsonPrimitive.content)
            Json.parseToJsonElement("""{"search":{"search":{"documents":[
                {"metadata":{"id":"same","name":"Launch"},"collection":null,"profile":null},
                {"metadata":null,"collection":{"id":"same","name":""},"profile":null},
                {"metadata":null,"collection":null,"profile":{"id":"profile","name":"","slug":"jamie"}},
                {"metadata":null,"collection":null,"profile":null},
                {"metadata":{"id":"spec","name":"MY_TEAM-SPEC-29"},"collection":null,"profile":null},
                {"metadata":{"id":"requirement","name":"WORKOPS-REQ-222"},"collection":null,"profile":null},
                {"metadata":{"id":"raw-id","name":"raw-id"},"collection":null,"profile":null},
                {"metadata":{"id":"same","name":"Launch"},"collection":null,"profile":null}
            ]}}}""")
        }
        val lookup = WorkOpsMentionLookup()
        assertEquals(listOf(mapOf("id" to "same", "label" to "Launch", "entityType" to "metadata"),
            mapOf("id" to "same", "label" to "Untitled collection", "entityType" to "collection"),
            mapOf("id" to "profile", "label" to "jamie", "entityType" to "profile"),
            mapOf("id" to "spec", "label" to "Specification", "entityType" to "metadata"),
            mapOf("id" to "requirement", "label" to "Requirement", "entityType" to "metadata"),
            mapOf("id" to "raw-id", "label" to "Untitled document", "entityType" to "metadata")), lookup.entities(gql, " launch "))
        assertFailsWith<IllegalArgumentException> { lookup.entities(gql, " ") }
    }

    @Test fun `mention lookup errors and cancellation are preserved rather than becoming empty results`() = runTest {
        val lookup = WorkOpsMentionLookup()
        val denied = client { _, _ -> error("Search access denied") }
        assertEquals("Search access denied", assertFailsWith<IllegalStateException> { lookup.profiles(denied, "") }.message)
        assertEquals("Search access denied", assertFailsWith<IllegalStateException> { lookup.entities(denied, "launch") }.message)
        assertFailsWith<CancellationException> { lookup.entities(client { _, _ -> throw CancellationException("removed") }, "launch") }
    }
}
