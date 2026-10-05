package bosca.security.service

import bosca.graphql.server.GraphQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SecurityExceptionTest {

    @Test
    fun `SecurityException stores message`() {
        val exception = SecurityException("Access denied")
        assertEquals("Access denied", exception.message)
    }

    @Test
    fun `SecurityException is a GraphQLException`() {
        val exception = SecurityException("Unauthorized")
        assertIs<GraphQLException>(exception)
    }

    @Test
    fun `SecurityException with empty message`() {
        val exception = SecurityException("")
        assertEquals("", exception.message)
    }

    @Test
    fun `SecurityException with detailed message`() {
        val msg = "User does not have permission to access resource X"
        val exception = SecurityException(msg)
        assertEquals(msg, exception.message)
    }
}
