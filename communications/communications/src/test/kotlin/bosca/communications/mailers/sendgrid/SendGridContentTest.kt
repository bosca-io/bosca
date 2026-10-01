package bosca.communications.mailers.sendgrid

import bosca.communications.mailers.Content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SendGridContentTest {

    @Test
    fun fieldPreservation() {
        val content = SendGridContent(type = "text/html", content = "<p>Hello</p>")
        assertEquals("text/html", content.type)
        assertEquals("<p>Hello</p>", content.content)
    }

    @Test
    fun implementsContentInterface() {
        val c: Content = SendGridContent(type = "text/plain", content = "hello")
        assertEquals("hello", c.content)
    }

    @Test
    fun equality() {
        val a = SendGridContent("text/plain", "body")
        val b = SendGridContent("text/plain", "body")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequality() {
        val a = SendGridContent("text/plain", "body1")
        val b = SendGridContent("text/plain", "body2")
        assertNotEquals(a, b)
    }

    @Test
    fun copy() {
        val original = SendGridContent("text/plain", "original")
        val copied = original.copy(content = "modified")
        assertEquals("modified", copied.content)
        assertEquals("text/plain", copied.type)
    }
}
