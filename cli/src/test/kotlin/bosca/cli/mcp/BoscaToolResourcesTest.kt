package bosca.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.*
import kotlin.test.*

class BoscaToolResourcesTest {
    private val id = "11111111-1111-1111-1111-111111111111"

    @Test
    fun `only successful item results produce validated content links`() {
        val result = BoscaToolResources.metadata("set_content", """{"id":"$id","title":"Report"}""")
        val link = assertIs<ResourceLink>(result.content.last())
        assertEquals("Report", link.name)
        assertEquals("bosca://content/metadata/$id", link.uri)
        assertEquals(1, BoscaToolResources.metadata("delete", """{"id":"$id","deleted":true}""").content.size)
        assertEquals(1, BoscaToolResources.metadata("get", """{"id":"../outside"}""").content.size)
        assertEquals(1, BoscaToolResources.metadata("list", """[{"id":"$id"}]""").content.size)
    }

    @Test
    fun `analytics resource preserves bounded payload and escapes saved query keys as one URI segment`() {
        val text = """{"records":[{"count":3}],"returnedRecords":1,"truncated":true,"cached":false}"""
        val args = buildJsonObject { put("action", "execute"); put("key", "metrics /?#✓") }
        val result = BoscaToolResources.analytics("queries", args, text)
        val resource = assertIs<TextResourceContents>(assertIs<EmbeddedResource>(result.content.last()).resource)
        assertEquals("bosca://analytics/queries/by-key/metrics%20%2F%3F%23%E2%9C%93/results", resource.uri)
        assertEquals("application/json", resource.mimeType)
        assertEquals(text, resource.text)
        val byId = BoscaToolResources.analytics("queries", JsonObject(args + ("id" to JsonPrimitive(id))), text)
        assertEquals("bosca://analytics/queries/$id/results", assertIs<EmbeddedResource>(byId.content.last()).resource.uri)
    }

    @Test
    fun `analytics artifacts retain their identity and deletes do not advertise deleted resources`() {
        for (kind in listOf("queries", "visualizations", "dashboards")) {
            val text = """{"id":"$id","name":"Saved result"}"""
            val result = BoscaToolResources.analytics(kind, mapOf("action" to JsonPrimitive("create")), text)
            assertEquals("bosca://analytics/$kind/$id", assertIs<ResourceLink>(result.content.last()).uri)
            assertEquals(1, BoscaToolResources.analytics(kind, mapOf("action" to JsonPrimitive("delete")), text).content.size)
        }
    }
}
