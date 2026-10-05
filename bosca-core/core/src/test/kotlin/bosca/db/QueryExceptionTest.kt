package bosca.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QueryExceptionTest {

    @Test
    fun `QueryException wraps cause message`() {
        val cause = RuntimeException("connection refused")
        val exception = QueryException(cause)
        assertEquals("connection refused", exception.message)
        assertIs<RuntimeException>(exception.cause)
    }

    @Test
    fun `QueryException wraps null message`() {
        val cause = RuntimeException()
        val exception = QueryException(cause)
        assertEquals(null, exception.message)
    }
}
