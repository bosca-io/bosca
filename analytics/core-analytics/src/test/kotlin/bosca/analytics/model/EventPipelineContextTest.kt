package bosca.analytics.model

import bosca.server.Headers
import bosca.server.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventPipelineContextTest {

    @Test
    fun `getHeaderValue returns value for existing header`() {
        val headers = headersOf("Content-Type", "application/json")
        val context = EventPipelineContext(headers)
        assertEquals("application/json", context.getHeaderValue("Content-Type"))
    }

    @Test
    fun `getHeaderValue returns null for missing header`() {
        val headers = headersOf()
        val context = EventPipelineContext(headers)
        assertNull(context.getHeaderValue("X-Missing"))
    }

    @Test
    fun `getHeaderValue is case-insensitive per HTTP spec`() {
        val headers = headersOf("Authorization", "Bearer token123")
        val context = EventPipelineContext(headers)
        assertEquals("Bearer token123", context.getHeaderValue("authorization"))
    }

    @Test
    fun `getHeaderValue with multiple headers returns first value`() {
        val headers = Headers.build {
            append("X-Custom", "value1")
            append("X-Custom", "value2")
        }
        val context = EventPipelineContext(headers)
        assertEquals("value1", context.getHeaderValue("X-Custom"))
    }

    @Test
    fun `getHeaderValue with empty headers`() {
        val headers = headersOf()
        val context = EventPipelineContext(headers)
        assertNull(context.getHeaderValue("Any-Header"))
    }

    @Test
    fun `getHeaderValue returns correct value among multiple headers`() {
        val headers = Headers.build {
            append("Accept", "text/html")
            append("Content-Type", "application/json")
            append("Authorization", "Bearer abc")
        }
        val context = EventPipelineContext(headers)
        assertEquals("text/html", context.getHeaderValue("Accept"))
        assertEquals("application/json", context.getHeaderValue("Content-Type"))
        assertEquals("Bearer abc", context.getHeaderValue("Authorization"))
    }
}
