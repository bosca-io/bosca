package bosca.cli.mcp

import bosca.cli.api.ContentApi
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class ContentToolRegistrarTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val parent = "22222222-2222-4222-8222-222222222222"
    private val template = "33333333-3333-4333-8333-333333333333"
    private val original = Json.parseToJsonElement("""{"document":{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"A title"}]},{"type":"paragraph","content":[{"type":"text","text":"Quoted passage","marks":[{"type":"italic"}]},{"type":"text","text":"1","marks":[{"type":"superscript"}]}]}]}}""")
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String?>()
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: ContentApi
    private var document = original
    private var documentTitle = "A title"
    private var failedOperation: String? = null

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            requests += request
            tokens += exchange.requestHeaders.getFirst("Authorization")
            val operation = request.getValue("operationName").jsonPrimitive.content
            val variables = request.getValue("variables").jsonObject
            val data = when (operation) {
                "AddMetadata" -> """{"metadata":{"add":{"id":"$id"}}}"""
                "GetMetadata" -> """{"metadata":{"id":"$id","slug":"article","version":3,"locked":false,"parentId":null,"name":"Article","type":"STANDARD","content":{"type":"bosca/v-document","length":null},"labels":[],"languageTag":"en","public":false,"publicContent":false,"publicSupplementary":false,"created":"2026-10-25T00:00:00Z","modified":"2026-10-25T00:00:00Z","uploaded":null,"ready":null,"attributes":{},"systemAttributes":{},"traitIds":[],"source":null,"metadataWorkflow":{"state":"pending","stateValid":null,"pending":null},"supplementary":[],"categories":[]}}"""
                "GetMetadataDocument" -> buildJsonObject { putJsonObject("metadata") { putJsonObject("document") {
                    put("title", documentTitle); put("content", document)
                    putJsonObject("template") { put("id", template); put("version", 2) }
                } } }.toString()
                "SetDocument" -> {
                    if (failedOperation != operation) {
                        val input = variables.getValue("document").jsonObject
                        document = input.getValue("content")
                        documentTitle = input.getValue("title").jsonPrimitive.content
                    }
                    """{"metadata":{"setMetadataDocument":true}}"""
                }
                "SetMetadataMarkdown" -> """{"metadata":{"setMetadataMarkdown":true}}"""
                "SetMetadataPublic" -> """{"metadata":{"setPublic":{"id":"$id"}}}"""
                else -> error("Unexpected operation $operation")
            }
            val response = (if (failedOperation == operation) """{"errors":[{"message":"permission denied"}]}"""
                else """{"data":{"content":$data}}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_document_fixture" } }
        api = ContentApi(network)
    }

    @AfterTest fun cleanup() {
        server.stop(0)
        network.http.dispatcher.executorService.shutdown()
        network.http.connectionPool.evictAll()
    }

    private suspend fun call(action: String, body: JsonObjectBuilder.() -> Unit) = ContentToolRegistrar.handleMetadata(api, buildJsonObject {
        put("action", action); body()
    })
    private fun body(result: CallToolResult) = Json.parseToJsonElement(assertIs<TextContent>(result.content.first()).text).jsonObject
    private fun operationNames() = requests.map { it.getValue("operationName").jsonPrimitive.content }
    private fun variables(index: Int = requests.lastIndex) = requests[index].getValue("variables").jsonObject

    @Test fun `inline document creation defaults title and type and sends the body once`() = runBlocking {
        val result = call("create") { put("name", "Article"); put("documentContent", original) }
        assertFalse(result.isError == true, result.toString())
        assertEquals(id, body(result).getValue("id").jsonPrimitive.content)
        assertEquals("bosca://content/metadata/$id", assertIs<ResourceLink>(result.content.last()).uri)
        assertEquals(listOf("AddMetadata"), operationNames())
        val saved = variables().getValue("metadata").jsonObject
        assertEquals("bosca/v-document", saved.getValue("contentType").jsonPrimitive.content)
        assertEquals("Article", saved.getValue("document").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(original, saved.getValue("document").jsonObject.getValue("content"))
        assertEquals(listOf<String?>("Bearer bsk_document_fixture"), tokens)
    }

    @Test fun `creation forwards template destination language and explicit document type`() = runBlocking {
        val result = call("create") {
            put("name", "Article"); put("documentTitle", "Titre"); put("documentContent", original)
            put("contentType", "application/custom-document"); put("languageTag", "fr")
            put("parentCollectionId", parent); put("templateId", template); put("templateVersion", 2)
            putJsonObject("attributes") { put("topic", "writing") }
        }
        assertFalse(result.isError == true)
        val metadata = variables().getValue("metadata").jsonObject
        assertEquals("application/custom-document", metadata.getValue("contentType").jsonPrimitive.content)
        assertEquals("fr", metadata.getValue("languageTag").jsonPrimitive.content)
        assertEquals(parent, metadata.getValue("parentCollectionId").jsonPrimitive.content)
        val input = metadata.getValue("document").jsonObject
        assertEquals("Titre", input.getValue("title").jsonPrimitive.content)
        assertEquals(template, input.getValue("templateMetadataId").jsonPrimitive.content)
        assertEquals(2, input.getValue("templateMetadataVersion").jsonPrimitive.int)
        assertEquals(listOf("AddMetadata"), operationNames())
    }

    @Test fun `title-only document creates a valid empty document and raw metadata stays raw`() = runBlocking {
        assertFalse(call("create") { put("name", "Empty"); put("documentTitle", "Empty") }.isError == true)
        assertEquals(Json.parseToJsonElement("""{"document":{"type":"doc","content":[]}}"""),
            variables().getValue("metadata").jsonObject.getValue("document").jsonObject.getValue("content"))
        assertFalse(call("create") { put("name", "Asset"); put("contentType", "image/png") }.isError == true)
        val saved = variables().getValue("metadata").jsonObject
        assertTrue(saved["document"] == null || saved["document"] == JsonNull)
        assertEquals("image/png", saved.getValue("contentType").jsonPrimitive.content)
    }

    @Test fun `structured read edit read round trip preserves exact document and selected version`() = runBlocking {
        val first = call("get_document") { put("id", id) }
        assertEquals(original, body(first).getValue("content"))
        assertEquals(template, body(first).getValue("templateId").jsonPrimitive.content)
        val updated = Json.parseToJsonElement("""{"document":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Revised"}]}]}}""")
        assertFalse(call("set_content") { put("id", id); put("documentContent", updated); put("documentTitle", "Revised title") }.isError == true)
        assertEquals(3, variables().getValue("version").jsonPrimitive.int)
        assertEquals("RESET", variables().getValue("collaborationSync").jsonPrimitive.content)
        val reread = call("get_document") { put("id", id); put("version", 3) }
        assertEquals(updated, body(reread).getValue("content"))
        assertEquals("Revised title", body(reread).getValue("title").jsonPrimitive.content)
        assertEquals(listOf("GetMetadata", "GetMetadataDocument", "GetMetadata", "SetDocument", "GetMetadataDocument"), operationNames())
    }

    @Test fun `markdown uses the existing server conversion and never uploads a text blob`() = runBlocking {
        assertFalse(call("set_content") { put("id", id); put("markdownContent", "# Heading\n\n**Body**") }.isError == true)
        assertEquals(listOf("GetMetadata", "SetMetadataMarkdown"), operationNames())
        assertEquals("Article", variables().getValue("title").jsonPrimitive.content)
        assertEquals("# Heading\n\n**Body**", variables().getValue("markdown").jsonPrimitive.content)
    }

    @Test fun `failed visibility change retains created identity and does not retry creation`() = runBlocking {
        failedOperation = "SetMetadataPublic"
        val result = call("create") { put("name", "Article"); put("documentContent", original); put("public", true) }
        assertTrue(result.isError == true)
        assertEquals(id, body(result).getValue("id").jsonPrimitive.content)
        assertContains(body(result).getValue("error").jsonPrimitive.content, "Metadata was created")
        assertIs<ResourceLink>(result.content.last())
        assertEquals(listOf("AddMetadata", "SetMetadataPublic"), operationNames())
    }

    @Test fun `invalid bodies fail before writes and denied reads and writes never fall back`(): Unit = runBlocking {
        assertTrue(call("set_content") { put("id", id); put("documentContent", original); put("markdownContent", "ignored?") }.isError == true)
        assertTrue(call("create") { put("name", "Article"); put("textContent", "ignored?") }.isError == true)
        assertTrue(call("create") { put("name", "Article"); put("documentContent", "not a document object") }.isError == true)
        assertTrue(requests.isEmpty())
        failedOperation = "GetMetadataDocument"
        assertTrue(call("get_document") { put("id", id); put("version", 3) }.isError == true)
        failedOperation = "AddMetadata"
        val denied = call("create") { put("name", "Article"); put("documentContent", original) }
        assertTrue(denied.isError == true)
        assertTrue(denied.content.none { it is ResourceLink })
        assertEquals(listOf("GetMetadataDocument", "AddMetadata"), operationNames())
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("create") { put("name", "Article"); put("documentContent", original) } }
    }
}
