package bosca.communications.mailers

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentInterfaceTest {

    private class TestContent(override val content: String) : Content

    @Test
    fun contentPropertyAccessible() {
        val c = TestContent("hello")
        assertEquals("hello", c.content)
    }

    @Test
    fun emptyContent() {
        val c = TestContent("")
        assertEquals("", c.content)
    }
}
