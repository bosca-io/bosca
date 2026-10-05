package bosca.graphql

import bosca.graphql.language.SourceLocation
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLException
import bosca.observability.ErrorCapture
import bosca.telemetry.Tracing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExceptionHandlerTest {

    private val handler = ExceptionHandler()

    @Test
    fun `handleException sanitizes unexpected exception messages`() = runTest {
        val exception = RuntimeException("Something went wrong")
        val error = handler.handleException(exception)
        assertEquals("Internal server error", error.message)
        assertEquals("500", error.extensions?.get("status")?.jsonPrimitive?.content)
        assertEquals(32, error.extensions?.get("traceId")?.jsonPrimitive?.content?.length)
    }

    @Test
    fun `handleException unwraps CompletionException`() = runTest {
        val cause = IllegalStateException("root cause")
        val wrapper = java.util.concurrent.CompletionException(cause)
        val error = handler.handleException(wrapper)
        assertEquals("root cause", error.message)
        assertEquals("400", error.extensions?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleException surfaces mapped client exception messages and statuses`() = runTest {
        val errors = listOf(
            handler.handleException(IllegalArgumentException("invalid argument")) to 400,
            handler.handleException(SecurityException("authentication required")) to 401,
            handler.handleException(NoSuchElementException("missing resource")) to 404,
        )

        assertEquals("invalid argument", errors[0].first.message)
        assertEquals("authentication required", errors[1].first.message)
        assertEquals("missing resource", errors[2].first.message)
        errors.forEach { (error, status) ->
            assertEquals(status.toString(), error.extensions?.get("status")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `CompletionException without a cause remains the reported exception`() = runTest {
        val error = handler.handleException(java.util.concurrent.CompletionException(null))
        assertEquals("Internal server error", error.message)
    }

    @Test
    fun `handle preserves a source location and path`() = runTest {
        val location = SourceLocation(2, 4, 8)
        val error = handler.handle(
            GraphQLException("invalid"),
            listOf("query", "field"),
            location,
            GraphQLContext.EMPTY,
        )

        assertEquals(listOf(location), error.locations)
        assertEquals(listOf("query", "field"), error.path)
    }

    @Test
    fun `handleException surfaces explicitly safe GraphQLException details`() = runTest {
        val exception = GraphQLException("denied", mapOf("reason" to JsonPrimitive("policy")))
        val error = handler.handleException(exception)
        assertEquals("denied", error.message)
        assertEquals("400", error.extensions?.get("status")?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive("policy"), error.extensions?.get("reason"))
        assertNotNull(error.extensions?.get("traceId"))
    }

    @Test
    fun `handleException handles null message`() = runTest {
        val exception = RuntimeException()
        val error = handler.handleException(exception)
        assertNotNull(error)
    }

    @Test
    fun `handleException surfaces a CodedError code in extensions`() = runTest {
        val exception = object : RuntimeException("nope"), CodedError {
            override val code = "TEST_CODE"
        }
        val error = handler.handleException(exception)
        assertEquals(JsonPrimitive("TEST_CODE"), error.extensions?.get("code"))
    }

    @Test
    fun `handleException finds a CodedError code in the cause chain`() = runTest {
        val coded = object : RuntimeException("inner"), CodedError {
            override val code = "WRAPPED_CODE"
        }
        val error = handler.handleException(RuntimeException("outer", coded))
        assertEquals(JsonPrimitive("WRAPPED_CODE"), error.extensions?.get("code"))
    }

    @Test
    fun `handleException without a CodedError sets no code extension`() = runTest {
        val error = handler.handleException(RuntimeException("plain"))
        assertEquals(null, error.extensions?.get("code"))
        assertNotNull(error.extensions?.get("traceId"))
    }

    @Test
    fun `handle uses the active Bosca coroutine trace id`() = runTest {
        val expected = Tracing.newTraceId()
        Tracing.withTrace(expected) {
            val error = handler.handle(RuntimeException("plain"), emptyList(), null, GraphQLContext.EMPTY)
            assertEquals(expected, error.extensions?.get("traceId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `trace id is forwarded to analytics context`() = runTest {
        val capturedContext = CompletableDeferred<Map<String, Any?>>()
        val capture = ErrorCapture { _, _, context -> capturedContext.complete(context) }
        val error = ExceptionHandler { capture }.handleException(RuntimeException("plain"))
        val traceId = error.extensions?.get("traceId")?.jsonPrimitive?.content

        assertNotNull(traceId)
        assertTrue(traceId.isNotBlank())
        assertEquals(traceId, capturedContext.await()["traceId"])
    }
}
