package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsDocumentReferenceLookupTest {
    @Test fun `reference searches retain missing Content and Bible names without manufacturing matches`() = runTest {
        assertEquals(emptyList(), lookup.searchMetadata(client { _, _ -> """{"search":{"search":null}}""" }, "Reference", emptyList()))
        assertNull(lookup.metadata(client { _, _ -> """{"content":{"metadata":null}}""" }, "document", null))
        val metadata = lookup.metadata(client { _, _ -> """{"content":{"metadata":{"id":"document","name":"Document","content":null}}}""" }, "document", null)
        assertEquals("", metadata?.get("contentType"))
        val matches = lookup.searchMetadata(client { _, _ -> """{"search":{"search":{"documents":[
          {"metadata":{"id":"document","name":"Document","content":null}},{"metadata":null}]}}}""" }, "Reference", emptyList())
        assertEquals(listOf(""), matches.map { it.getValue("contentType") })
        val bibles = lookup.bibles(client { _, _ -> """{"content":{"findMetadata":[
          {"id":"named","name":"Stored Bible","bible":{"name":""}},{"id":"unnamed","name":"","bible":{"name":""}}]}}""" }, 0)
        assertEquals(listOf("Stored Bible", "Untitled Bible"), bibles.map { it.getValue("label") })
        assertEquals(emptyList(), lookup.findScripture(client { _, _ -> throw AssertionError("Blank reference cannot search") }, "bible", " "))
        val unresolved = lookup.findScripture(client { _, _ -> """{"content":{"metadata":{"bible":{"find":[
          {"reference":{"human":"Unresolved","usfm":"GEN.1"},"chapter":null}]}}}}""" }, "bible", "Unresolved")
        assertEquals(emptyList(), unresolved)
    }
    @Test fun `missing scripture components chapters books and attribution retain explicit outcomes`() = runTest {
        fun response(chapter: String, book: String = "{\"nameLong\":\"Genesis\"}") =
            """{"content":{"metadata":{"bible":{"name":"Translation","styles":[],"book":$book,"chapter":$chapter}}}}"""
        val chapter = """{"component":{"_":"cc","type":"PARAGRAPH","style":null,"components":[]},"attribution":null}"""
        val passage = lookup.scripture(client { _, _ -> response(chapter) }, "bible", "GEN.1").single()
        assertEquals("", passage["copyright"])
        assertEquals("", passage["citation"])
        assertEquals("", passage["informationUrl"])
        assertEquals("Chapter is unavailable.", assertFailsWith<NoSuchElementException> {
            lookup.scripture(client { _, _ -> response("null") }, "bible", "GEN.1")
        }.message)
        assertEquals("Scripture is unavailable.", assertFailsWith<NoSuchElementException> {
            lookup.scripture(client { _, _ -> response("""{"component":null,"attribution":null}""") }, "bible", "GEN.1")
        }.message)
        assertEquals("Book is unavailable.", assertFailsWith<NoSuchElementException> {
            lookup.scripture(client { _, _ -> response(chapter, "null") }, "bible", "GEN.1")
        }.message)
    }
    private val lookup = WorkOpsDocumentReferenceLookup()
    private fun client(handler: (String, JsonObject) -> String) = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            Json.parseToJsonElement(handler(requireNotNull(operationName), variables ?: buildJsonObject {}))
    }

    @Test fun `metadata search retains template filters and selected references retain their version`() = runTest {
        val gql = client { operation, variables ->
            when (operation) {
                "WorkOpsDocumentMetadataChoices" -> {
                    assertEquals("launch", variables.getValue("query").jsonPrimitive.content)
                    assertEquals(listOf("_type = \"metadata\"", "content.type = \"text/plain\""), variables.getValue("filters").jsonArray.map { it.jsonPrimitive.content })
                    """{"search":{"search":{"documents":[{"metadata":{"id":"one","name":"Launch notes","content":{"type":"text/plain"}}},{"metadata":null}]}}}"""
                }
                "WorkOpsDocumentMetadataReference" -> {
                    assertEquals(4, variables.getValue("version").jsonPrimitive.int)
                    """{"content":{"metadata":{"id":"one","name":"Launch notes","content":{"type":"text/plain"}}}}"""
                }
                else -> error(operation)
            }
        }
        assertEquals(listOf(mapOf("id" to "one", "label" to "Launch notes", "contentType" to "text/plain")),
            lookup.searchMetadata(gql, "launch", listOf("content.type = \"text/plain\"")))
        assertEquals("Launch notes", lookup.metadata(gql, "one", 4)?.get("label"))
        assertNull(lookup.metadata(client { _, _ -> """{"content":{"metadata":null}}""" }, "private", null))
    }

    @Test fun `Bible listing preserves offset and human search offers entered chapter and verse choices`() = runTest {
        var requests = 0
        val gql = client { operation, variables ->
            requests++
            when (operation) {
                "WorkOpsDocumentBibleChoices" -> {
                    assertTrue(variables.isEmpty())
                    val preceding = (1..25).joinToString(",") { """{"id":"bible-$it","name":"Bible $it","bible":{"name":"Translation $it"}}""" }
                    """{"content":{"findMetadata":[$preceding,{"id":"disabled","name":"Disabled variant","bible":null},{"id":"bible","name":"Stored name","bible":{"name":"Example translation"}}]}}"""
                }
                "WorkOpsDocumentScriptureChoices" -> {
                    assertEquals("Genesis 1:1-2", variables.getValue("query").jsonPrimitive.content)
                    """{"content":{"metadata":{"bible":{"find":[{"reference":{"human":"Genesis 1:1-2","usfm":"GEN.1.1+GEN.1.2"},"chapter":{"reference":{"human":"Genesis 1","usfm":"GEN.1"},"verses":[{"human":"Genesis 1:1","usfm":"GEN.1.1"},{"human":"Genesis 1:2","usfm":"GEN.1.2"}]}}]}}}}"""
                }
                else -> error(operation)
            }
        }
        assertEquals("Example translation", lookup.bibles(gql, 25).single()["label"])
        assertEquals(listOf("GEN.1.1+GEN.1.2", "GEN.1", "GEN.1.1", "GEN.1.2"), lookup.findScripture(gql, "bible", " Genesis 1:1-2 ").map { it["id"] })
        assertEquals(emptyList(), lookup.findScripture(gql, "bible", " "))
        assertEquals(2, requests)
        assertFailsWith<IllegalArgumentException> { lookup.bibles(gql, -1) }
    }

    @Test fun `selected verses use canonical component filtering and preserve styles attribution and chapter order`() = runTest {
        val requested = mutableListOf<String>()
        val gql = client { operation, variables ->
            assertEquals("WorkOpsDocumentScripturePassage", operation)
            requested += variables.getValue("chapter").jsonPrimitive.content
            assertEquals("GEN", variables.getValue("book").jsonPrimitive.content)
            """{"content":{"metadata":{"bible":{"name":"Example translation","styles":[{"id":"wj","fontWeight":"bold"}],"book":{"nameLong":"Genesis"},"chapter":{"component":{"_":"cc","type":"PARAGRAPH","style":null,"components":[{"_":"vs","reference":{"usfm":"GEN.1.1"}},{"_":"t","text":"First verse","style":null},{"_":"ve"},{"_":"vs","reference":{"usfm":"GEN.1.2"}},{"_":"t","text":"Second verse","style":null},{"_":"ve"}]},"attribution":{"copyright":"Example copyright","citation":"Example citation","informationUrl":"https://example.test/translation"}}}}}}"""
        }
        val verse = lookup.scripture(gql, "bible", "GEN.1.2").single()
        assertEquals("Example translation — Genesis 1:2", verse["label"])
        assertTrue(verse.getValue("components").contains("Second verse"))
        assertFalse(verse.getValue("components").contains("First verse"))
        assertEquals("Example copyright", verse["copyright"])
        assertEquals("Example citation", verse["citation"])
        assertEquals("https://example.test/translation", verse["informationUrl"])
        assertEquals("wj", Json.parseToJsonElement(verse.getValue("styles")).jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val chapter = lookup.scripture(gql, "bible", "GEN.1").single()
        assertTrue(chapter.getValue("components").contains("First verse"))
        requested.clear(); lookup.scripture(gql, "bible", "GEN.2+GEN.1")
        assertEquals(listOf("GEN.2", "GEN.1"), requested)
    }

    @Test fun `missing Bibles lookup failures and cancellation remain explicit`() = runTest {
        val missing = client { _, _ -> """{"content":{"metadata":null}}""" }
        assertFailsWith<NoSuchElementException> { lookup.findScripture(missing, "private", "Genesis 1") }
        assertEquals(emptyList(), lookup.scripture(missing, "private", "GEN.1"))
        assertFailsWith<IllegalArgumentException> { lookup.scripture(missing, "bible", "GEN") }
        val cancelled = client { _, _ -> throw CancellationException("unmounted") }
        assertFailsWith<CancellationException> { lookup.bibles(cancelled, 0) }
        assertFailsWith<CancellationException> { lookup.scripture(cancelled, "bible", "GEN.1") }
        assertFailsWith<CancellationException> { lookup.searchMetadata(cancelled, "query", emptyList()) }
    }
}
