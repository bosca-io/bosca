package bosca.cli.mcp

import bosca.cli.api.ContentImages
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.*

class ImageToolRegistrarTest {
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: ContentImages
    private val id = "11111111-1111-4111-8111-111111111111"
    private val source = "22222222-2222-4222-8222-222222222222"
    private val parent = "33333333-3333-4333-8333-333333333333"
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String?>()
    private val downloadHeaders = mutableListOf<Pair<String?, String?>>()
    private val bytes = byteArrayOf(1, 2, 3)
    private var failedOperation: String? = null
    private var readable = true
    private var uploaded = true
    private var image = true
    private var attached = true
    private var attachmentWarning: String? = null

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            requests += request
            tokens += exchange.requestHeaders.getFirst("Authorization")
            val operation = request.getValue("operationName").jsonPrimitive.content
            val content = if (readable) """{"type":"${if (image) "image/png" else "text/plain"}","length":3}""" else "null"
            val value = """{"id":"$id","version":1,"name":"Bird","uploaded":${if (uploaded) "\"2026-10-24T00:00:00Z\"" else "null"},"content":$content}"""
            val data = when (operation) {
                "GenerateContentImage" -> """{"images":{"generate":$value}}"""
                "EditContentImage" -> """{"images":{"edit":$value}}"""
                "GetContentImage" -> """{"metadata":$value}"""
                "AttachContentImage" -> buildJsonObject {
                    putJsonObject("images") { putJsonObject("attach") {
                        put("success", attached); put("attached", attached); put("targetLanguageTag", "fr")
                        put("collaborationUpdated", attached); put("optimized", attachmentWarning == null)
                        put("warning", attachmentWarning); put("error", if (attached) null else "Choose attributeKey")
                        val attribute = buildJsonObject {
                            put("key", "hero"); put("name", "Hero"); put("description", "Cover image")
                            put("relationship", "image.featured"); put("list", false)
                            put("targetSize", JsonArray(listOf(JsonPrimitive(740), JsonPrimitive(416))))
                            put("defaultAspectRatio", 16.0 / 9.0)
                        }
                        put("attribute", if (attached) attribute else JsonNull)
                        put("availableAttributes", JsonArray(listOf(attribute)))
                        put("crop", JsonNull)
                    } }
                }.toString()
                "GetMetadataDownload" -> """{"metadata":{"content":{"type":"image/png","length":3,"urls":{"download":{"url":"http://127.0.0.1:${server.address.port}/image","headers":[{"name":"X-Image-Signature","value":"signed-fixture"}]}}}}}"""
                else -> error("Unexpected operation $operation")
            }
            val response = (if (failedOperation == operation) """{"errors":[{"message":"permission denied"}]}""" else """{"data":{"content":$data}}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/image") { exchange ->
            downloadHeaders += exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("X-Image-Signature")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_image_fixture" } }
        api = ContentImages(network)
    }
    @AfterTest fun cleanup() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    private suspend fun call(action: String, extra: String = "{}") = ImageToolRegistrar.handle(api, buildJsonObject {
        put("action", action); Json.parseToJsonElement(extra).jsonObject.forEach { (key, value) -> put(key, value) }
    })
    private fun assertImage(result: CallToolResult) {
        assertFalse(result.isError == true, result.toString())
        assertEquals(id, Json.parseToJsonElement(assertIs<TextContent>(result.content[0]).text).jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("bosca://content/metadata/$id", assertIs<ResourceLink>(result.content[1]).uri)
        val image = assertIs<ImageContent>(result.content[2])
        assertEquals("image/png", image.mimeType)
        assertContentEquals(bytes, Base64.getDecoder().decode(image.data))
    }

    @Test fun `generate edit and get return stored identity and signed image previews`() = runBlocking {
        assertImage(call("generate", """{"prompt":"a bird","parentCollectionId":"$parent"}"""))
        assertEquals(JsonPrimitive("Generated Image"), requests[0].getValue("variables").jsonObject["name"])
        assertEquals(JsonPrimitive(parent), requests[0].getValue("variables").jsonObject["parentCollectionId"])
        assertImage(call("edit", """{"sourceId":"$source","prompt":"blue","name":"Blue bird"}"""))
        assertEquals(JsonPrimitive(source), requests[2].getValue("variables").jsonObject["sourceId"])
        assertEquals(JsonPrimitive("Blue bird"), requests[2].getValue("variables").jsonObject["name"])
        assertImage(call("get", """{"id":"$id"}"""))
        assertEquals(listOf("GenerateContentImage", "GetMetadataDownload", "EditContentImage", "GetMetadataDownload", "GetContentImage", "GetMetadataDownload"), requests.map { it.getValue("operationName").jsonPrimitive.content })
        assertTrue(tokens.all { it == "Bearer bsk_image_fixture" })
        assertEquals(List<Pair<String?, String?>>(3) { null to "signed-fixture" }, downloadHeaders)
    }

    @Test fun `preview failure preserves stored identity without repeating generation`() = runBlocking {
        failedOperation = "GetMetadataDownload"
        val result = call("generate", """{"prompt":"bird"}""")
        assertFalse(result.isError == true)
        assertIs<ResourceLink>(result.content[1])
        assertContains(assertIs<TextContent>(result.content.last()).text, "Use get")
        assertTrue(result.content.none { it is ImageContent })
        assertEquals(2, requests.size)
        assertTrue(downloadHeaders.isEmpty())
        failedOperation = null
        assertImage(call("get", """{"id":"$id"}"""))
        assertEquals(1, requests.count { it.getValue("operationName").jsonPrimitive.content == "GenerateContentImage" })
    }

    @Test fun `unreadable and incomplete content retain the saved link`() = runBlocking {
        readable = false
        assertFalse(call("generate", """{"prompt":"bird"}""").isError == true)
        readable = true
        uploaded = false
        val result = call("edit", """{"sourceId":"$source","prompt":"blue"}""")
        assertFalse(result.isError == true)
        assertContains(assertIs<TextContent>(result.content.last()).text, "incomplete")
        assertTrue(downloadHeaders.isEmpty())
        assertEquals(2, requests.size)
    }

    @Test fun `invalid arguments and non-images fail without downloads`() = runBlocking {
        for ((action, extra) in listOf("unknown" to "{}", "generate" to "{}", "generate" to """{"prompt":" "}""", "edit" to """{"prompt":"blue"}""", "get" to """{"id":"invalid"}""", "generate" to """{"prompt":"bird","parentCollectionId":"invalid"}""")) {
            assertTrue(call(action, extra).isError == true)
        }
        assertTrue(requests.isEmpty())
        image = false
        assertTrue(call("get", """{"id":"$id"}""").isError == true)
        assertTrue(downloadHeaders.isEmpty())
    }

    @Test fun `mutation error is not retried and cancellation propagates`(): Unit = runBlocking {
        failedOperation = "GenerateContentImage"
        assertTrue(call("generate", """{"prompt":"bird"}""").isError == true)
        assertEquals(1, requests.size)
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("get", """{"id":"$id"}""") }
    }

    @Test fun `attach passes typed crop and target under caller credentials without downloading`() = runBlocking {
        val result = call("attach", """{"id":"$source","targetMetadataId":"$id","attributeKey":"hero","crop":{"top":1,"left":2,"width":300,"height":200}}""")
        assertFalse(result.isError == true, result.toString())
        assertEquals("bosca://content/metadata/$id", assertIs<ResourceLink>(result.content[1]).uri)
        val variables = requests.single().getValue("variables").jsonObject
        assertEquals(source, variables.getValue("imageMetadataId").jsonPrimitive.content)
        assertEquals(id, variables.getValue("targetMetadataId").jsonPrimitive.content)
        assertEquals("hero", variables.getValue("attributeKey").jsonPrimitive.content)
        assertEquals(300.0, variables.getValue("crop").jsonObject.getValue("width").jsonPrimitive.double)
        assertEquals(listOf<String?>("Bearer bsk_image_fixture"), tokens)

        attachmentWarning = "Relationship optimization unavailable"
        val translated = call("attach", """{"id":"$source","targetCollectionId":"$parent","targetLanguageTag":"fr"}""")
        assertFalse(translated.isError == true)
        assertContains(assertIs<TextContent>(translated.content[0]).text, attachmentWarning.orEmpty())
        assertEquals("bosca://content/collections/$parent", assertIs<ResourceLink>(translated.content[1]).uri)
        assertEquals("fr", requests.last().getValue("variables").jsonObject.getValue("targetLanguageTag").jsonPrimitive.content)
        assertEquals(2, requests.size)
        assertTrue(downloadHeaders.isEmpty())
    }

    @Test fun `ambiguous attachment returns choices without a saved link or retry`() = runBlocking {
        attached = false
        val result = call("attach", """{"id":"$source","targetMetadataId":"$id"}""")
        assertTrue(result.isError == true)
        val text = assertIs<TextContent>(result.content.single()).text
        assertContains(text, "availableAttributes")
        assertContains(text, "image.featured")
        assertEquals(1, requests.size)
        assertTrue(downloadHeaders.isEmpty())
    }

    @Test fun `invalid attachment arguments and denied mutations cannot be retried as reads`(): Unit = runBlocking {
        for (arguments in listOf(
            """{"id":"$source"}""",
            """{"id":"$source","targetMetadataId":"$id","targetCollectionId":"$parent"}""",
            """{"id":"$source","targetMetadataId":"$id","targetLanguageTag":"fr"}""",
            """{"id":"$source","targetMetadataId":"$id","crop":{"width":10}}""",
        )) assertTrue(call("attach", arguments).isError == true)
        assertTrue(requests.isEmpty())
        failedOperation = "AttachContentImage"
        assertTrue(call("attach", """{"id":"$source","targetMetadataId":"$id"}""").isError == true)
        assertEquals(1, requests.size)
        assertTrue(downloadHeaders.isEmpty())
        network.tokenProvider = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { call("attach", """{"id":"$source","targetMetadataId":"$id"}""") }
    }
}
