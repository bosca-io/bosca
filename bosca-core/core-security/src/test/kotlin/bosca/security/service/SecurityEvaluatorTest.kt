package bosca.security.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecurityEvaluatorTest {

    @Test
    fun `throwUnauthorized throws SecurityException with correct message`() {
        val exception = assertFailsWith<SecurityException> {
            val securityService = io.mockk.mockk<SecurityService>()
            val evaluator = GroupEvaluator(securityService)
            evaluator.throwUnauthorized()
        }
        assertEquals("Unauthorized access", exception.message)
    }

    @Test
    fun `SecurityException has correct message`() {
        val exception = SecurityException("test message")
        assertEquals("test message", exception.message)
    }
}
