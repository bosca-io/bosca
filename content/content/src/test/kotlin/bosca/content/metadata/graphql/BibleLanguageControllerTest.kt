package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleLanguage
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class BibleLanguageControllerTest {

    private val controller = BibleLanguageController()

    private fun createLanguage(
        iso: String = "eng",
        name: String = "English",
        nameLocal: String = "English",
        script: String = "Latin",
        scriptCode: String = "Latn",
        scriptDirection: String = "LTR"
    ) = BibleLanguage(
        metadataId = UUID.random(),
        version = 1,
        variant = "default",
        iso = iso,
        name = name,
        nameLocal = nameLocal,
        script = script,
        scriptCode = scriptCode,
        scriptDirection = scriptDirection,
        sort = 0
    )

    @Test
    fun `iso returns language iso code`() {
        assertEquals("eng", controller.iso(createLanguage(iso = "eng")))
    }

    @Test
    fun `name returns language name`() {
        assertEquals("English", controller.name(createLanguage(name = "English")))
    }

    @Test
    fun `nameLocal returns local name`() {
        assertEquals("Deutsch", controller.nameLocal(createLanguage(nameLocal = "Deutsch")))
    }

    @Test
    fun `script returns script name`() {
        assertEquals("Latin", controller.script(createLanguage(script = "Latin")))
    }

    @Test
    fun `scriptCode returns script code`() {
        assertEquals("Latn", controller.scriptCode(createLanguage(scriptCode = "Latn")))
    }

    @Test
    fun `scriptDirection returns script direction`() {
        assertEquals("RTL", controller.scriptDirection(createLanguage(scriptDirection = "RTL")))
    }
}
