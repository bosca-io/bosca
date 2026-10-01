package bosca.graphql.client

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoscaGraphQLClientInfoTest {
    @Test
    fun `session identity is optional and missing or blank values omit the header`() {
        val withoutSession = BoscaGraphQLClientInfo("installation-1", "reader", "1.0")
        assertNull(withoutSession.sessionId)
        assertNull(withoutSession.headers()[BoscaGraphQLHeaders.SESSION_ID])
        val withSession = withoutSession.copy(sessionId = "session-1")
        assertEquals("session-1", withSession.headers()[BoscaGraphQLHeaders.SESSION_ID])
        for (session in listOf("", " ", "\t")) {
            assertNull(withoutSession.copy(sessionId = session).headers()[BoscaGraphQLHeaders.SESSION_ID])
        }
    }

    @Test
    fun `client identity requires nonblank values`() {
        assertFailsWith<IllegalArgumentException> {
            BoscaGraphQLClientInfo("", "reader", "1.0")
        }
        assertFailsWith<IllegalArgumentException> {
            BoscaGraphQLClientInfo("installation-1", "", "1.0")
        }
        assertFailsWith<IllegalArgumentException> {
            BoscaGraphQLClientInfo("installation-1", "reader", "")
        }
    }
}
