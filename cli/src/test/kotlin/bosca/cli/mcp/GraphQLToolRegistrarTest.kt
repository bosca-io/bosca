package bosca.cli.mcp

import bosca.cli.api.NetworkClient
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.GraphQLResponse
import bosca.graphql.server.*
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

/** Real HTTP and the Bosca GraphQL engine validate live discovery and operation forwarding. */
class GraphQLToolRegistrarTest {
    private lateinit var server: HttpServer
    private lateinit var network: NetworkClient
    private val requests = mutableListOf<JsonObject>()
    private val tokens = mutableListOf<String>()
    private var status = 200
    private var introspection = true
    private var failure: String? = null
    private var edits = 0
    private val engine = GraphQL(ExecutableSchema.fromSdl("""
        "An authoring request"
        input DraftInput { "A topic title" title: String! count: Int = 2 }
        enum Stage { DRAFT PUBLISHED }
        interface Named { name: String! }
        type Item implements Named { name: String! }
        type Query {
          "Documents available for authoring"
          items: [Item!]!
          echo(value: String): String
          denied: String
          stage: Stage
        }
        type Mutation { save(input: DraftInput!): String! }
    """.trimIndent(), runtimeWiring {
        type("Query") {
            field("items") { emptyList<Map<String, Any?>>() }
            field("echo") { it.getArgument<String>("value") }
            field("denied") { error("Access denied") }
        }
        type("Mutation") { field("save") { edits++; "saved" } }
    }))

    @BeforeTest fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            requests += request
            tokens += exchange.requestHeaders.getFirst("Authorization")
            val result = failure ?: runBlocking {
                engine.execute(GraphQLRequest(query = request.getValue("query").jsonPrimitive.content,
                    variables = request["variables"]?.jsonObject.orEmpty(), operationName = request["operationName"]?.jsonPrimitive?.content,
                    introspectionEnabled = introspection)).toJson().toString()
            }
            val bytes = result.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        network = NetworkClient("http://127.0.0.1:${server.address.port}/graphql").apply { tokenProvider = { "bsk_graphql_fixture" } }
    }

    @AfterTest fun cleanup() { server.stop(0); network.http.dispatcher.executorService.shutdown(); network.http.connectionPool.evictAll() }
    private suspend fun call(action: String, extra: String = "{}") = GraphQLToolRegistrar.handle(network.boscaGraphql,
        JsonObject(Json.parseToJsonElement(extra).jsonObject + ("action" to JsonPrimitive(action))))

    @Test fun `roots and type discovery retain wrappers arguments defaults descriptions enums and interfaces`(): Unit = runBlocking {
        val roots = call("roots")
        assertFalse(roots.isError == true, roots.toString())
        assertContains(roots.structuredContent.toString(), "Documents available for authoring")
        assertContains(roots.structuredContent.toString(), "NON_NULL")
        assertContains(roots.structuredContent.toString(), "LIST")
        assertContains(call("type", """{"typeName":"DraftInput"}""").structuredContent.toString(), "\"defaultValue\":\"2\"")
        assertContains(call("type", """{"typeName":"Mutation"}""").structuredContent.toString(), "\"args\"")
        assertContains(call("type", """{"typeName":"Stage"}""").structuredContent.toString(), "PUBLISHED")
        assertContains(call("type", """{"typeName":"Item"}""").structuredContent.toString(), "Named")
        assertEquals(JsonNull, call("type", """{"typeName":"Missing"}""").structuredContent?.get("data")?.jsonObject?.get("__type"))
        assertEquals(JsonPrimitive("Missing"), requests.last().getValue("variables").jsonObject["name"])
        assertTrue(tokens.all { it == "Bearer bsk_graphql_fixture" })
        assertEquals(0, edits)
    }

    @Test fun `schema search matches names and descriptions with stable offset pagination`(): Unit = runBlocking {
        val first = call("search", """{"query":"AUTHORING","limit":1}""").structuredContent?.getValue("data")?.jsonObject
        assertEquals(JsonPrimitive(2), first?.get("count"))
        assertEquals(JsonPrimitive(true), first?.get("hasMore"))
        val second = call("search", """{"query":"authoring","offset":1,"limit":1}""").structuredContent?.getValue("data")?.jsonObject
        assertEquals(JsonPrimitive(false), second?.get("hasMore"))
        assertNotEquals(first?.get("results"), second?.get("results"))
        val input = call("search", """{"query":"topic title"}""").structuredContent?.getValue("data")?.jsonObject?.getValue("results")?.jsonArray?.single()?.jsonObject
        assertEquals(JsonPrimitive("INPUT_FIELD"), input?.get("kind"))
        assertEquals(JsonPrimitive("DraftInput"), input?.get("type"))
        assertEquals(JsonArray(emptyList()), call("search", """{"query":"authoring","offset":2147483647}""").structuredContent?.getValue("data")?.jsonObject?.get("results"))
    }

    @Test fun `execution preserves the document explicit null variables and selected named operation`(): Unit = runBlocking {
        val document = "query A(\$value: String) { echo(value: \$value) } query B { items { name } }"
        val result = call("execute", buildJsonObject { put("query", document); put("operationName", "A"); putJsonObject("variables") { put("value", JsonNull) } }.toString())
        assertFalse(result.isError == true, result.toString())
        assertEquals(JsonPrimitive(document), requests.single()["query"])
        assertEquals(JsonPrimitive("A"), requests.single()["operationName"])
        assertEquals(JsonNull, requests.single().getValue("variables").jsonObject["value"])
        assertEquals(JsonNull, result.structuredContent?.getValue("data")?.jsonObject?.get("echo"))
        val resource = assertIs<EmbeddedResource>(result.content[1]).resource
        assertEquals("bosca://graphql/results/execute", resource.uri)
        assertEquals("application/json", resource.mimeType)
        assertEquals(result.structuredContent, Json.parseToJsonElement(assertIs<TextContent>(result.content[0]).text).jsonObject)
    }

    @Test fun `a mutation is sent once and partial GraphQL errors retain their data`(): Unit = runBlocking {
        assertFalse(call("execute", """{"query":"mutation Save(${'$'}input: DraftInput!) { save(input: ${'$'}input) }","variables":{"input":{"title":"draft"}}}""").isError == true)
        assertEquals(1, edits)
        assertEquals(1, requests.size)
        val partial = call("execute", """{"query":"{ echo(value: \"visible\") denied }"}""")
        assertTrue(partial.isError == true)
        assertEquals(JsonPrimitive("visible"), partial.structuredContent?.getValue("data")?.jsonObject?.get("echo"))
        assertTrue(partial.structuredContent?.getValue("errors")?.jsonArray?.isNotEmpty() == true)
    }

    @Test fun `introspection denial is visible and never substitutes a local schema`(): Unit = runBlocking {
        introspection = false
        for ((action, extra) in listOf("roots" to "{}", "type" to """{"typeName":"Item"}""", "search" to """{"query":"authoring"}""")) {
            assertTrue(call(action, extra).isError == true)
        }
        assertEquals(3, requests.size)
        assertFalse(call("execute", """{"query":"{ items { name } }"}""").isError == true)
    }

    @Test fun `HTTP failures and mutation errors have no operation retry or auth fallback`(): Unit = runBlocking {
        for (code in listOf(401, 403, 503)) {
            status = code; failure = "request denied"
            assertTrue(call("execute", """{"query":"mutation { save(input: {title: \"draft\"}) }"}""").isError == true)
        }
        assertEquals(3, requests.size)
        assertEquals(0, edits)
        assertTrue(tokens.all { it == "Bearer bsk_graphql_fixture" })
    }

    @Test fun `invalid arguments never reach the endpoint`(): Unit = runBlocking {
        val invalid = listOf("type" to "{}", "type" to """{"typeName":"bad name"}""", "search" to """{"query":" "}""",
            "search" to """{"query":"Item","limit":101}""", "search" to """{"query":"Item","offset":-1}""",
            "execute" to """{"query":"query A { items { name } } query B { echo }"}""",
            "execute" to """{"query":"{ echo }","operationName":"Missing"}""",
            "execute" to """{"query":"{ echo }","variables":[1]}""", "execute" to """{"query":"subscription { event }"}""",
            "execute" to """{"query":"{"}""", "unknown" to "{}")
        for ((action, extra) in invalid) assertTrue(call(action, extra).isError == true, "$action $extra")
        assertTrue(requests.isEmpty())
    }

    @Test fun `cancellation is propagated instead of becoming a tool result`(): Unit = runBlocking {
        val cancelled = CancellationException("cancelled")
        val client = object : GraphQLClient {
            override suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse = throw cancelled
        }
        assertSame(cancelled, assertFailsWith<CancellationException> {
            GraphQLToolRegistrar.handle(client, mapOf("action" to JsonPrimitive("roots")))
        })
    }
}
