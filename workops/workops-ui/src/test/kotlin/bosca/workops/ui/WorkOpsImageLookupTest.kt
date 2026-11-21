package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkOpsImageLookupTest {
    private val lookup = WorkOpsImageLookup()
    private val id = "11111111-2222-4333-8444-555555555555"
    private fun client(handler: (String, JsonObject) -> String) = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?) =
            Json.parseToJsonElement(handler(requireNotNull(operationName), variables ?: buildJsonObject {}))
    }
    private fun image(uploaded: Boolean = true, type: String = "image/avif", viewable: Boolean = true) =
        """{"content":{"metadata":{"id":"$id","name":"Diagram","uploaded":${if (uploaded) "\"2026-11-14T00:00:00Z\"" else "null"},"content":{"type":"$type","length":3000000000,"urls":{"download":${if (viewable) "{\"url\":\"https://api.test/signed\"}" else "null"}}}}}}"""
    private val upload = """{"id":"$id","content":{"urls":{"upload":{"url":"https://api.test/prefix/api/v1/content/metadata/upload?id=$id&signature=fixture","headers":[{"name":"Authorization","value":"Bearer fixture"}]}}}}"""

    @Test fun `search and resolution omit inaccessible Content objects and incomplete download targets`() = runTest {
        val contents = listOf("null", """{"type":"image/png","urls":null}""", """{"type":"image/png","urls":{"download":null}}""",
            """{"type":"text/plain","urls":{"download":{"url":"/signed"}}}""")
        for (content in contents) {
            val metadata = """{"id":"$id","name":"Image","uploaded":"2026-11-17","content":$content}"""
            assertNull(lookup.resolve(client { _, _ -> """{"content":{"metadata":$metadata}}""" }, id))
            val result = lookup.search(client { _, _ -> """{"search":{"search":{"documents":[{"metadata":$metadata},{"metadata":null}]}}}""" }, "", 0)
            assertEquals(mapOf("items" to "[]"), result)
        }
        assertEquals(mapOf("items" to "[]"), lookup.search(client { _, _ -> """{"search":{"search":null}}""" }, "", 0))
    }

    @Test fun `upload preparation rejects unavailable nested targets for both creation and retry`() = runTest {
        for (content in listOf("null", """{"urls":null}""", """{"urls":{"upload":null}}""")) {
            val metadata = """{"id":"$id","content":$content}"""
            for (existing in listOf("", id)) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    lookup.prepare(client { operation, _ ->
                        if (operation == "WorkOpsPrepareImage") """{"content":{"metadata":{"add":$metadata}}}"""
                        else """{"content":{"metadata":$metadata}}"""
                    }, "image.png", "image/png", 1, existing)
                }
                assertEquals(if (existing.isBlank()) "Image upload is unavailable." else "Image upload is unavailable or you do not have access to it.", failure.message)
            }
        }
        assertFailsWith<NoSuchElementException> {
            lookup.prepare(client { _, _ -> """{"content":{"metadata":null}}""" }, "image.png", "image/png", 1, id)
        }
        assertFailsWith<IllegalArgumentException> {
            lookup.prepare(client { _, _ -> error("Must validate length before preparing Content") }, "image.png", "image/png", -1, "")
        }
        assertFailsWith<IllegalArgumentException> {
            lookup.prepare(client { _, _ -> """{"content":{"metadata":{"add":${upload.replace("/prefix/api/v1/content/metadata/upload", "/unsupported")}}}}""" }, "image.png", "image/png", 1, "")
        }
    }

    @Test fun `ready refusal remains a failure after an uploaded image has been resolved`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            lookup.complete(client { operation, _ ->
                if (operation == "WorkOpsImage") image() else """{"content":{"metadata":{"setMetadataReady":false}}}"""
            }, id)
        }
        assertEquals("Could not complete the image upload.", error.message)
    }

    @Test fun `image search uses raw offset pages while filtering unavailable and unuploaded results`() = runTest {
        val gql = client { operation, variables ->
            assertEquals("WorkOpsImageChoices", operation); assertEquals("diagram", variables.getValue("query").jsonPrimitive.content)
            assertEquals(25, variables.getValue("offset").jsonPrimitive.int)
            val rows = (0..25).joinToString(",") { index ->
                """{"metadata":{"id":"$id","name":"Diagram $index","uploaded":${if (index == 0) "null" else "\"2026-11-14T00:00:00Z\""},"content":{"type":"image/svg+xml","urls":{"download":{"url":"/signed"}}}}}"""
            }
            """{"search":{"search":{"documents":[$rows]}}}"""
        }
        val page = lookup.search(gql, "diagram", 25)
        assertEquals("0", page["previousOffset"]); assertEquals("50", page["nextOffset"])
        val rows = Json.parseToJsonElement(page.getValue("items")).jsonArray
        assertEquals(24, rows.size); assertEquals("Diagram 1", rows.first().jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("/content/image/$id", rows.first().jsonObject.getValue("src").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { lookup.search(gql, "", -1) }
    }

    @Test fun `image resolution requires uploaded image Content VIEW and preserves unavailable identities in callers`() = runTest {
        assertEquals("image/avif", lookup.resolve(client { _, _ -> image() }, id)?.get("contentType"))
        assertNull(lookup.resolve(client { _, _ -> image(uploaded = false) }, id))
        assertNull(lookup.resolve(client { _, _ -> image(type = "text/plain") }, id))
        assertNull(lookup.resolve(client { _, _ -> image(viewable = false) }, id))
        assertNull(lookup.resolve(client { _, _ -> """{"content":{"metadata":null}}""" }, id))
    }

    @Test fun `prepare creates an unready Content draft with Long size and retry refreshes the same draft upload target`() = runTest {
        val calls = mutableListOf<String>()
        val gql = client { operation, variables ->
            calls += operation
            when (operation) {
                "WorkOpsPrepareImage" -> {
                    assertEquals(3000000000L, variables.getValue("length").jsonPrimitive.long)
                    assertEquals("image/avif", variables.getValue("type").jsonPrimitive.content)
                    """{"content":{"metadata":{"add":$upload}}}"""
                }
                "WorkOpsRetryImage" -> {
                    assertEquals(id, variables.getValue("id").jsonPrimitive.content)
                    """{"content":{"metadata":$upload}}"""
                }
                else -> error(operation)
            }
        }
        val prepared = lookup.prepare(gql, "diagram.avif", "image/avif", 3000000000L, "")
        assertEquals("/api/v1/content/metadata/upload?id=$id&signature=fixture", prepared["url"])
        assertEquals("Authorization", Json.parseToJsonElement(prepared.getValue("headers")).jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(id, lookup.prepare(gql, "diagram.avif", "image/avif", 3000000000L, id)["id"])
        assertEquals(listOf("WorkOpsPrepareImage", "WorkOpsRetryImage"), calls)
        assertFailsWith<IllegalArgumentException> { lookup.prepare(gql, "", "image/avif", 1, "") }
        assertFailsWith<IllegalArgumentException> { lookup.prepare(gql, "data.txt", "text/plain", 1, "") }
    }

    @Test fun `completion requires the stored upload before marking ready and preserves cancellation and transport failure`() = runTest {
        val calls = mutableListOf<String>()
        val gql = client { operation, _ -> calls += operation; when (operation) {
            "WorkOpsImage" -> image()
            "WorkOpsCompleteImage" -> """{"content":{"metadata":{"setMetadataReady":true}}}"""
            else -> error(operation)
        } }
        assertEquals(id, lookup.complete(gql, id)["id"]); assertEquals(listOf("WorkOpsImage", "WorkOpsCompleteImage"), calls)
        assertFailsWith<NoSuchElementException> { lookup.complete(client { operation, _ -> assertEquals("WorkOpsImage", operation); image(uploaded = false) }, id) }
        assertFailsWith<IllegalStateException> { lookup.resolve(client { _, _ -> error("upstream failure") }, id) }
        assertFailsWith<CancellationException> { lookup.prepare(client { _, _ -> throw CancellationException("cancelled") }, "diagram.png", "image/png", 1, "") }
    }
}
