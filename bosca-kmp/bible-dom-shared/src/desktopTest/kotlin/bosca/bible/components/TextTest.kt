package bosca.bible.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextTest {

    @Test
    fun `creation preserves text value`() {
        val text = Text("Hello world", null)
        assertEquals("Hello world", text.text)
    }

    @Test
    fun `style can be null`() {
        val text = Text("test", null)
        assertNull(text.style)
    }

    @Test
    fun `style can be set`() {
        val style = bosca.bible.style.Style(id = "text-style")
        val text = Text("test", style)
        assertEquals("text-style", text.style!!.id)
    }

    @Test
    fun `implements IComponent`() {
        val text = Text("test", null)
        assertIs<IComponent>(text)
    }

    @Test
    fun `toString contains text value`() {
        val text = Text("sample text", null)
        val str = text.toString()
        assertTrue(str.contains("sample text"))
    }

    @Test
    fun `empty text string`() {
        val text = Text("", null)
        assertEquals("", text.text)
    }

    @Test
    fun `text with special characters`() {
        val text = Text("He said \"hello\" & 'goodbye'", null)
        assertEquals("He said \"hello\" & 'goodbye'", text.text)
    }
}
