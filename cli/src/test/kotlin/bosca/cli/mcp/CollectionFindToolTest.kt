package bosca.cli.mcp

import bosca.cli.api.ContentApi
import bosca.cli.api.NetworkClient
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class CollectionFindToolTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val row = """{"id":"$id","locked":false,"slug":null,"traitIds":[],"name":"Accessible topic","labels":[],"created":"2026-10-27T00:00:00Z","modified":"2026-10-27T00:00:00Z","attributes":{"type":"Topic"},"systemAttributes":{},"ready":null,"public":false,"publicList":false,"ordering":null,"categories":[],"collectionWorkflow":{"state":"pending","stateValid":null,"pending":null},"supplementary":[]}"""
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String?>()
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private lateinit var api: ContentApi
    private var status = 200
    private var response = ""

    @BeforeTest fun setup() {
        response = """{"data":{"content":{"findCollections":[$row]}}}"""
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            tokens += exchange.requestHeaders.getFirst("Authorization")
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_collection_fixture" } }
        api = ContentApi(network)
    }

    @AfterTest fun cleanup() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    private suspend fun call(extra: String = "{}") = ContentToolRegistrar.findCollections(api,
        JsonObject(mapOf("filters" to Json.parseToJsonElement("""[{"key":"type","value":"Topic"}]""")) + Json.parseToJsonElement(extra).jsonObject))

    @Test fun `topic discovery uses the existing typed query with direct scoped identity`(): Unit = runBlocking {
        val result = call()
        assertFalse(result.isError == true, result.toString())
        assertEquals(Json.parseToJsonElement("""[{"id":"$id","name":"Accessible topic","public":false}]"""),
            Json.parseToJsonElement(assertIs<TextContent>(result.content.single()).text))
        assertEquals("FindCollections", requests.single().getValue("operationName").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"query":{"attributes":[{"attributes":[{"key":"type","value":"Topic"}]}],"offset":0,"limit":20}}"""), requests.single()["variables"])
        assertEquals(listOf<String?>("Bearer bsk_collection_fixture"), tokens)
        assertFalse(requests.single().getValue("query").jsonPrimitive.content.contains("__schema"))
    }

    @Test fun `multiple filters use AND semantics and preserve explicit values and offset`(): Unit = runBlocking {
        val filters = """[{"key":"type","value":"Topic"},{"key":"label","value":"quoted \"title\""},{"key":"empty","value":""}]"""
        assertFalse(call("""{"filters":$filters,"offset":40,"limit":10}""").isError == true)
        val query = requests.single().getValue("variables").jsonObject.getValue("query").jsonObject
        assertEquals(JsonPrimitive(40), query["offset"])
        assertEquals(JsonPrimitive(10), query["limit"])
        assertEquals(Json.parseToJsonElement(filters), query.getValue("attributes").jsonArray.single().jsonObject["attributes"])
    }

    @Test fun `empty authorized results stay empty without fallback or counts`(): Unit = runBlocking {
        response = """{"data":{"content":{"findCollections":[]}}}"""
        assertEquals("[]", assertIs<TextContent>(call().content.single()).text)
        assertEquals(1, requests.size)
    }

    @Test fun `invalid filters and pagination do not reach the API`(): Unit = runBlocking {
        for (input in listOf("""{"filters":null}""", """{"filters":[]}""", """{"filters":[{"key":" ","value":"Topic"}]}""",
            """{"filters":[{"key":"type"}]}""", """{"offset":-1}""", """{"limit":0}""", """{"limit":101}""", """{"offset":"bad"}""")) {
            assertTrue(call(input).isError == true, input)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `HTTP and GraphQL failures do not retry as another identity`(): Unit = runBlocking {
        for (code in listOf(401, 403, 503, 200)) {
            status = code; response = """{"errors":[{"message":"access denied"}]}"""
            assertTrue(call().isError == true)
        }
        assertEquals(4, requests.size)
        assertTrue(tokens.all { it == "Bearer bsk_collection_fixture" })
    }

    @Test fun `cancellation propagates through the find operation`(): Unit = runBlocking {
        val cancelled = CancellationException("cancelled")
        network.tokenProvider = { throw cancelled }
        assertSame(cancelled, assertFailsWith<CancellationException> { call() })
        assertTrue(requests.isEmpty())
    }
}
