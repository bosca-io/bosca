package bosca.languages.graphql

import bosca.languages.model.Language
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageControllerTest {

    private val controller = LanguageController()

    @Test
    fun `tag returns language tag`() {
        val language = Language(tag = "en", name = "English", localName = "English")
        assertEquals("en", controller.tag(language))
    }

    @Test
    fun `name returns language name`() {
        val language = Language(tag = "es", name = "Spanish", localName = "Espanol")
        assertEquals("Spanish", controller.name(language))
    }

    @Test
    fun `localName returns localized name`() {
        val language = Language(tag = "fr", name = "French", localName = "Francais")
        assertEquals("Francais", controller.localName(language))
    }

    @Test
    fun `attributes returns null when not set`() {
        val language = Language(tag = "en", name = "English", localName = "English")
        assertNull(controller.attributes(language))
    }

    @Test
    fun `attributes returns JsonElement when set`() {
        val attrs = JsonObject(mapOf("direction" to JsonPrimitive("rtl")))
        val language = Language(tag = "ar", name = "Arabic", localName = "Arabic", attributes = attrs)
        assertEquals(attrs, controller.attributes(language))
    }

    @Test
    fun `controller resolves fields for regional language tag`() {
        val language = Language(tag = "pt-BR", name = "Portuguese (Brazil)", localName = "Portugues (Brasil)")
        assertEquals("pt-BR", controller.tag(language))
        assertEquals("Portuguese (Brazil)", controller.name(language))
        assertEquals("Portugues (Brasil)", controller.localName(language))
    }
}
