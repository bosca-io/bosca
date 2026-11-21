package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import kotlin.test.*

class WorkOpsFormChoicesLookupTest {
    private fun client(node: String, handler: suspend (String, JsonObject?, String?) -> JsonElement): GraphQLClient = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            if (operationName != "WorkOpsTaskCreateForm") return handler(query, variables, operationName)
            assertEquals("custom-task", variables?.get("key")?.jsonPrimitive?.content)
            return Json.parseToJsonElement("""{"formSchemas":{"byKey":{"id":"form","key":"custom-task","name":"Custom task","type":"WORK_OPS","version":4,"schema":{},"uiSchema":{"version":1,"layout":[{"type":"section","children":[$node]}]}}}}""")
        }
    }
    private val lookup = WorkOpsFormChoicesLookup("http://localhost/graphql")

    @Test fun `API choices keep optional authentication root arrays and HTTP errors explicit`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authorizations = mutableListOf<String?>()
        var status = 200
        server.createContext("/api/v1/s/choices") { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val bytes = """[{"id":"visible"},{"id":null},{}]""".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = WorkOpsFormChoicesLookup("http://127.0.0.1:${server.address.port}/graphql")
            val node = """{"type":"field","control":"api-script-select","apiScript":{"key":"choices","valuePath":"id","labelPath":null}}"""
            val gql = client(node) { _, _, _ -> error("Must use the configured script") }
            for (token in listOf(null, "Bearer current-caller")) {
                val page = withRenderContext(RenderContext(gql = gql, token = token)) {
                    remote.choices(gql, "custom-task", 4, "form-0-0", null, 0)
                }
                assertEquals("""[{"value":"visible","label":"visible"},{"value":"","label":""},{"value":"","label":""}]""", page["options"])
            }
            assertEquals(listOf(null, "Bearer current-caller"), authorizations)
            status = 503
            withRenderContext(RenderContext(gql = gql)) {
                assertEquals("API-script choices failed (503).", assertFailsWith<IllegalStateException> {
                    remote.choices(gql, "custom-task", 4, "form-0-0", null, 0)
                }.message)
            }
            val invalid = client(node.replace("\"key\":\"choices\"", "\"key\":\"choices\",\"method\":\"PUT\"")) { _, _, _ -> error("Unexpected") }
            withRenderContext(RenderContext(gql = invalid)) {
                assertFailsWith<IllegalArgumentException> { remote.choices(invalid, "custom-task", 4, "form-0-0", null, 0) }
            }
        } finally { server.stop(0) }
    }

    @Test fun `configured script search waits for input and unavailable profile search stays empty`() = runTest {
        val script = client("""{"type":"field","control":"api-script-select","apiScript":{"key":"choices","searchParam":"query","valuePath":"id"}}""") { _, _, _ -> error("Must not execute a provider") }
        assertEquals(mapOf("options" to "[]"), lookup.choices(script, "custom-task", 4, "form-0-0", null, 0))
        val profiles = client("""{"type":"field","control":"profile-select"}""") { _, _, _ ->
            Json.parseToJsonElement("""{"search":{"search":null}}""")
        }
        assertEquals(mapOf("options" to "[]"), lookup.choices(profiles, "custom-task", 4, "form-0-0", "Person", 0))
    }

    @Test fun `stored GraphQL configuration preserves variables search paths and string choices`() = runTest {
        val gql = client("""{"type":"field","property":"owner","control":"graphql-select","graphql":{"query":"query Choices","variables":{"constant":false,"q":"initial"},"searchVariable":"q","resultPath":"items.0.choices","valuePath":"identity.id","labelPath":"title"}}""") { query, variables, operation ->
            assertEquals("query Choices", query); assertNull(operation)
            assertEquals(JsonPrimitive(false), variables?.get("constant")); assertEquals(JsonPrimitive("owner"), variables?.get("q"))
            Json.parseToJsonElement("""{"items":[{"choices":[{"identity":{"id":42},"title":"Visible owner"},{"identity":{"id":null},"title":null}]}]}""")
        }
        assertEquals("[]", lookup.choices(gql, "custom-task", 4, "form-0-0", null, 0)["options"])
        val options = Json.parseToJsonElement(lookup.choices(gql, "custom-task", 4, "form-0-0", "owner", 0).getValue("options")).jsonArray
        assertEquals(JsonPrimitive("42"), options[0].jsonObject["value"])
        assertEquals(JsonPrimitive("Visible owner"), options[0].jsonObject["label"])
        assertEquals(JsonPrimitive(""), options[1].jsonObject["value"])
        assertEquals(JsonPrimitive(""), options[1].jsonObject["label"])
        assertEquals("The task form changed. Reload the form.", lookup.choices(gql, "custom-task", 3, "form-0-0", "owner", 0)["error"])
        assertFailsWith<IllegalArgumentException> { lookup.choices(gql, "custom-task", 4, "form-1", "owner", 0) }
    }

    @Test fun `static choices return the complete source for one client load`() = runTest {
        val gql = client("""{"type":"field","control":"graphql-select","graphql":{"query":"query All","resultPath":"choices","valuePath":"id","labelPath":"name"}}""") { _, variables, _ ->
            assertEquals(JsonObject(emptyMap()), variables)
            buildJsonObject { put("choices", JsonArray((1..31).map { buildJsonObject { put("id", it); put("name", "Choice $it") } })) }
        }
        val snapshot = lookup.choices(gql, "custom-task", 4, "form-0-0", null, 0)
        assertNull(snapshot["nextOffset"]); assertNull(snapshot["previousOffset"])
        assertEquals(31, Json.parseToJsonElement(snapshot.getValue("options")).jsonArray.size)
    }

    @Test fun `profile choices allow initial search and preserve private result gaps and paging`() = runTest {
        val gql = client("""{"type":"field","control":"profile-select"}""") { _, variables, operation ->
            assertEquals("WorkOpsProfileChoices", operation); assertEquals(JsonPrimitive(""), variables?.get("query")); assertEquals(JsonPrimitive(20), variables?.get("offset"))
            buildJsonObject { put("search", buildJsonObject { put("search", buildJsonObject { put("documents", JsonArray((1..21).map { index -> buildJsonObject {
                put("profile", if (index == 1) JsonNull else buildJsonObject { put("id", "profile-$index"); put("name", "Profile $index"); put("slug", "profile-$index") })
            } })) }) }) }
        }
        val page = lookup.choices(gql, "custom-task", 4, "form-0-0", null, 20)
        assertEquals("40", page["nextOffset"]); assertEquals("0", page["previousOffset"])
        assertEquals(19, Json.parseToJsonElement(page.getValue("options")).jsonArray.size)
    }

    @Test fun `API script GET and POST forward caller authentication and configured parameters`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Triple<String, String, String>>()
        server.createContext("/prefix/api/v1/s/task-choices") { exchange ->
            assertEquals("Bearer current-caller", exchange.requestHeaders.getFirst("Authorization"))
            requests += Triple(exchange.requestMethod, exchange.requestURI.rawQuery.orEmpty(), exchange.requestBody.readBytes().decodeToString())
            val body = """{"choices":[{"id":"visible","label":"Visible choice"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val remote = WorkOpsFormChoicesLookup("http://127.0.0.1:${server.address.port}/prefix/graphql")
            for (method in listOf("GET", "POST")) {
                val gql = client("""{"type":"field","control":"api-script-select","apiScript":{"key":"task-choices","method":"$method","params":{"flag":false,"list":[1,null,"two"],"object":{"id":1}},"searchParam":"q","resultPath":"choices","valuePath":"id","labelPath":"label"}}""") { _, _, _ -> error("Unexpected GraphQL query") }
                withRenderContext(RenderContext(gql = gql, token = "current-caller")) {
                    assertTrue(remote.choices(gql, "custom-task", 4, "form-0-0", "a & b", 0).getValue("options").contains("Visible choice"))
                }
            }
            assertEquals("GET", requests[0].first)
            assertTrue("q=a%20%26%20b" in requests[0].second, requests[0].second)
            assertTrue("list=1%2C%2Ctwo" in requests[0].second)
            assertTrue("object=%5Bobject%20Object%5D" in requests[0].second)
            assertEquals("", requests[0].third)
            val post = Json.parseToJsonElement(requests[1].third).jsonObject
            assertEquals(JsonPrimitive(false), post["flag"]); assertEquals(JsonPrimitive("a & b"), post["q"])
            assertTrue(post["list"] is JsonArray); assertTrue(post["object"] is JsonObject)
        } finally { server.stop(0) }
    }

    @Test fun `numeric parameters use browser number formatting rather than JSON lexical spelling`() {
        for ((source, expected) in mapOf("1.0" to "1", "-0.0" to "0", "1.25" to "1.25", "1e-6" to "0.000001", "1e-7" to "1e-7", "1e20" to "100000000000000000000", "1e21" to "1e+21", "9007199254740993" to "9007199254740992")) {
            assertEquals(expected, formChoiceString(Json.parseToJsonElement(source)), source)
        }
    }

    @Test fun `authorization failures cancellation and invalid result paths remain failures`() = runTest {
        val node = """{"type":"field","control":"graphql-select","graphql":{"query":"query Choices","resultPath":"missing","valuePath":"id"}}"""
        val denied = client(node) { _, _, _ -> error("Permission denied") }
        assertEquals("Permission denied", assertFailsWith<IllegalStateException> { lookup.choices(denied, "custom-task", 4, "form-0-0", "", 0) }.message)
        val cancelled = client(node) { _, _, _ -> throw CancellationException("Closed") }
        assertFailsWith<CancellationException> { lookup.choices(cancelled, "custom-task", 4, "form-0-0", "", 0) }
        val missing = client(node) { _, _, _ -> JsonObject(emptyMap()) }
        assertFailsWith<IllegalStateException> { lookup.choices(missing, "custom-task", 4, "form-0-0", "", 0) }
    }

    @Test fun `unavailable and non WorkOps forms never execute their stored choice queries`() = runTest {
        for (form in listOf("null", """{"id":"form","key":"custom-task","name":"Other form","type":"SUBMISSION","version":4,"schema":{},"uiSchema":{"version":1,"layout":[]}}""")) {
            val gql = object : GraphQLClient {
                override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
                    assertEquals("WorkOpsTaskCreateForm", operationName)
                    return Json.parseToJsonElement("""{"formSchemas":{"byKey":$form}}""")
                }
            }
            assertTrue(lookup.choices(gql, "custom-task", 4, "form-0-0", "", 0).getValue("error").contains("Reload the form"))
        }
        assertFailsWith<IllegalArgumentException> {
            lookup.choices(client("{}") { _, _, _ -> error("No query expected") }, "custom-task", 4, "form-0-0", "", -1)
        }
    }

    @Test fun `configured GraphQL searches page their results and default labels to the value path`() = runTest {
        val gql = client("""{"type":"field","control":"graphql-select","graphql":{"query":"query Choices","searchVariable":"search","resultPath":"choices","valuePath":"id"}}""") { _, variables, _ ->
            assertEquals(JsonPrimitive("query"), variables?.get("search"))
            buildJsonObject { put("choices", JsonArray((0..25).map { buildJsonObject { put("id", it) } })) }
        }
        val first = lookup.choices(gql, "custom-task", 4, "form-0-0", "query", 0)
        assertEquals("25", first["nextOffset"]); assertNull(first["previousOffset"])
        assertEquals(25, Json.parseToJsonElement(first.getValue("options")).jsonArray.size)
        val last = lookup.choices(gql, "custom-task", 4, "form-0-0", "query", 25)
        assertNull(last["nextOffset"]); assertEquals("0", last["previousOffset"])
        val choice = Json.parseToJsonElement(last.getValue("options")).jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("25"), choice["value"]); assertEquals(choice["value"], choice["label"])
    }

    @Test fun `missing or local field configuration is rejected before any remote operation`() = runTest {
        for (field in listOf("""{"type":"field","control":"input"}""", """{"type":"field","control":"graphql-select"}""", """{"type":"field","control":"api-script-select"}""")) {
            val gql = client(field) { _, _, _ -> error("No choice operation should execute") }
            assertFailsWith<IllegalStateException> { lookup.choices(gql, "custom-task", 4, "form-0-0", "", 0) }
        }
    }
}
