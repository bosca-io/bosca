package bosca.analytics.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementTest {

    @Test
    fun `Element has sensible defaults`() {
        val element = Element()
        assertNull(element.id)
        assertNull(element.type)
        assertTrue(element.content?.isEmpty() ?: true)
        assertEquals(JsonNull, element.extras)
    }

    @Test
    fun `Element with all fields`() {
        val content = listOf(
            Content(id = "c1", type = "text"),
            Content(id = "c2", type = "image")
        )
        val element = Element(
            id = "element-1",
            type = "section",
            content = content,
            extras = JsonPrimitive("extra-data")
        )
        assertEquals("element-1", element.id)
        assertEquals("section", element.type)
        assertEquals(2, element.content?.size)
        assertEquals(JsonPrimitive("extra-data"), element.extras)
    }

    @Test
    fun `Element with null content`() {
        val element = Element(id = "e1", type = "button", content = null)
        assertNull(element.content)
    }

    @Test
    fun `Element equality`() {
        val a = Element(id = "e1", type = "t")
        val b = Element(id = "e1", type = "t")
        assertEquals(a, b)
    }
}
