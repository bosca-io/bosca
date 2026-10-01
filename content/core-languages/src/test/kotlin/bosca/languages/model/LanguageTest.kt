package bosca.languages.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageTest {

    @Test
    fun `Language stores tag, name, and localName`() {
        val language = Language(tag = "en", name = "English", localName = "English")
        assertEquals("en", language.tag)
        assertEquals("English", language.name)
        assertEquals("English", language.localName)
    }

    @Test
    fun `Language attributes defaults to null`() {
        val language = Language(tag = "en", name = "English", localName = "English")
        assertNull(language.attributes)
    }

    @Test
    fun `Language with attributes`() {
        val attrs = JsonObject(mapOf("direction" to JsonPrimitive("ltr")))
        val language = Language(tag = "en", name = "English", localName = "English", attributes = attrs)
        assertEquals(attrs, language.attributes)
    }

    @Test
    fun `Language with non-Latin script`() {
        val language = Language(tag = "ja", name = "Japanese", localName = "\u65E5\u672C\u8A9E")
        assertEquals("ja", language.tag)
        assertEquals("Japanese", language.name)
        assertEquals("\u65E5\u672C\u8A9E", language.localName)
    }

    @Test
    fun `Language with regional tag`() {
        val language = Language(tag = "en-US", name = "English (United States)", localName = "English")
        assertEquals("en-US", language.tag)
    }

    @Test
    fun `Language data class equality`() {
        val l1 = Language(tag = "es", name = "Spanish", localName = "Espa\u00F1ol")
        val l2 = Language(tag = "es", name = "Spanish", localName = "Espa\u00F1ol")
        assertEquals(l1, l2)
    }

    @Test
    fun `Language data class copy`() {
        val original = Language(tag = "fr", name = "French", localName = "Fran\u00E7ais")
        val modified = original.copy(localName = "Fran\u00E7ais (Canada)")
        assertEquals("fr", modified.tag)
        assertEquals("Fran\u00E7ais (Canada)", modified.localName)
    }

    @Test
    fun `Language with RTL direction attribute`() {
        val attrs = JsonObject(mapOf("direction" to JsonPrimitive("rtl")))
        val language = Language(tag = "ar", name = "Arabic", localName = "\u0627\u0644\u0639\u0631\u0628\u064A\u0629", attributes = attrs)
        assertEquals("rtl", (language.attributes as JsonObject)["direction"]?.let { (it as JsonPrimitive).content })
    }
}
