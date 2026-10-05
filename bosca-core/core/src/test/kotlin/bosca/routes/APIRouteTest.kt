package bosca.routes

import bosca.graphql.server.GraphQLException
import bosca.server.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class APIRouteTest {

    @Test
    fun `illegal argument is a bad request with its message`() {
        assertMapping(
            IllegalArgumentException("invalid argument"),
            HttpStatusCode.BadRequest,
            "invalid argument",
        )
    }

    @Test
    fun `other client exceptions preserve their messages and statuses`() {
        assertMapping(
            SecurityException("authentication required"),
            HttpStatusCode.Unauthorized,
            "authentication required",
        )
        assertMapping(GraphQLException("invalid operation"), HttpStatusCode.BadRequest, "invalid operation")
        assertMapping(IllegalStateException("invalid state"), HttpStatusCode.BadRequest, "invalid state")
        assertMapping(NoSuchElementException("missing resource"), HttpStatusCode.NotFound, "missing resource")
    }

    @Test
    fun `unexpected exception is an internal server error without its message`() {
        val exception = RuntimeException("database unavailable")
        val error = exception.toAPIError()

        assertEquals(HttpStatusCode.InternalServerError, error.status)
        assertSame(exception, error.exception)
        assertNull(error.message)
    }

    private fun assertMapping(exception: Throwable, expectedStatus: HttpStatusCode, expectedMessage: String) {
        val error = exception.toAPIError()

        assertEquals(expectedStatus, error.status)
        assertSame(exception, error.exception)
        assertEquals(expectedMessage, error.message)
    }
}
