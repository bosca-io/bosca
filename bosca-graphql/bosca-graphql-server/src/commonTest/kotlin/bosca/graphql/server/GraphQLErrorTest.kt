package bosca.graphql.server

import bosca.graphql.language.SourceLocation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**the GraphQLError model + ExecutionResult serialization + the default exception handler. */
class GraphQLErrorTest {

    @Test
    fun `a full error serializes message, locations, path, and extensions`() {
        val error = GraphQLError(
            message = "boom",
            locations = listOf(SourceLocation(line = 2, column = 5, offset = 10)),
            path = listOf("user", 0, "name"),
            extensions = mapOf("code" to JsonPrimitive("FORBIDDEN")),
        )
        val expected = buildJsonObject {
            put("message", "boom")
            put("locations", JsonArray(listOf(buildJsonObject { put("line", 2); put("column", 5) })))
            put("path", JsonArray(listOf(JsonPrimitive("user"), JsonPrimitive(0), JsonPrimitive("name"))))
            put("extensions", buildJsonObject { put("code", "FORBIDDEN") })
        }
        assertEquals(expected, error.toJson())
    }

    @Test
    fun `a minimal error serializes just the message`() {
        assertEquals(buildJsonObject { put("message", "oops") }, GraphQLError("oops").toJson())
    }

    @Test
    fun `a successful result carries only data`() {
        val data = buildJsonObject { put("x", 1) }
        assertEquals(buildJsonObject { put("data", data) }, ExecutionResult(data = data).toJson())
    }

    @Test
    fun `partial data and errors coexist in one result`() {
        val data = buildJsonObject { put("user", JsonNull) }
        val result = ExecutionResult(data = data, errors = listOf(GraphQLError("nope", path = listOf("user"))))
        val json = result.toJson()
        assertEquals(data, json["data"])
        assertEquals(1, (json["errors"] as JsonArray).size)
    }

    @Test
    fun `a request error omits the data key entirely`() {
        val json = ExecutionResult.ofErrors(listOf(GraphQLError("bad request"))).toJson()
        assertFalse(json.containsKey("data"))
        assertTrue(json.containsKey("errors"))
    }

    @Test
    fun `the default handler sanitizes an unexpected exception and preserves path and location`() = runTest {
        val error = DefaultDataFetcherExceptionHandler.handle(
            RuntimeException("kaboom"),
            path = listOf("a", "b"),
            location = SourceLocation(1, 3, 0),
            context = GraphQLContext.EMPTY,
        )
        assertEquals("Internal server error", error.message)
        assertEquals(listOf("a", "b"), error.path)
        assertEquals(listOf(SourceLocation(1, 3, 0)), error.locations)
    }

    @Test
    fun `the default handler surfaces extensions from a GraphQLException and falls back on a null message`() = runTest {
        val withExtensions = DefaultDataFetcherExceptionHandler.handle(
            GraphQLException("denied", mapOf("code" to JsonPrimitive("FORBIDDEN"))),
            path = emptyList(),
            location = null,
            context = GraphQLContext.EMPTY,
        )
        assertEquals(mapOf("code" to JsonPrimitive("FORBIDDEN")), withExtensions.extensions)

        val fallback = DefaultDataFetcherExceptionHandler.handle(
            RuntimeException(),
            path = emptyList(),
            location = null,
            context = GraphQLContext.EMPTY,
        )
        assertEquals("Internal server error", fallback.message)
    }

    @Test
    fun `an exception handler receives request context`() = runTest {
        var received: GraphQLContext? = null
        val handler = DataFetcherExceptionHandler { exception, path, _, context ->
            received = context
            GraphQLError(exception.message ?: "error", path = path)
        }

        val requestContext = GraphQLContext(mapOf("request" to "one"))
        val executable = ExecutableSchema.fromSdl(
            "type Query { boom: String }",
            runtimeWiring { type("Query") { field("boom") { error("kaboom") } } },
        )
        val result = GraphQLExecutor(executable, exceptionHandler = handler).execute(
            bosca.graphql.parser.Parser.parse("{ boom }"),
            context = requestContext,
        )
        assertEquals(requestContext, received)
        assertEquals("kaboom", result.errors.single().message)
    }
}
