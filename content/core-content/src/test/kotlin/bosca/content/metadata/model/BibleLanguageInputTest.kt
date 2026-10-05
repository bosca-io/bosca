package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class BibleLanguageInputTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `BibleLanguageInput stores all properties`() {
        val input = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        assertEquals("eng", input.iso)
        assertEquals("English", input.name)
        assertEquals("English", input.nameLocal)
        assertEquals("Latin", input.script)
        assertEquals("Latn", input.scriptCode)
        assertEquals("LTR", input.scriptDirection)
    }

    @Test
    fun `BibleLanguageInput with RTL direction`() {
        val input = BibleLanguageInput(
            iso = "arb",
            name = "Arabic",
            nameLocal = "العربية",
            script = "Arabic",
            scriptCode = "Arab",
            scriptDirection = "RTL"
        )
        assertEquals("arb", input.iso)
        assertEquals("Arabic", input.name)
        assertEquals("العربية", input.nameLocal)
        assertEquals("RTL", input.scriptDirection)
    }

    @Test
    fun `data class equality`() {
        val input1 = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val input2 = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `data class inequality with different values`() {
        val input1 = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val input2 = BibleLanguageInput(
            iso = "fra",
            name = "French",
            nameLocal = "Francais",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        assertNotEquals(input1, input2)
    }

    @Test
    fun `toLanguage maps all fields correctly`() {
        val input = BibleLanguageInput(
            iso = "spa",
            name = "Spanish",
            nameLocal = "Espanol",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val language = input.toLanguage(testId, 2, "standard", 0)
        assertEquals(testId, language.metadataId)
        assertEquals(2, language.version)
        assertEquals("standard", language.variant)
        assertEquals("spa", language.iso)
        assertEquals("Spanish", language.name)
        assertEquals("Espanol", language.nameLocal)
        assertEquals("Latin", language.script)
        assertEquals("Latn", language.scriptCode)
        assertEquals("LTR", language.scriptDirection)
        assertEquals(0, language.sort)
    }

    @Test
    fun `toLanguage with different sort values`() {
        val input = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val lang1 = input.toLanguage(testId, 1, "v1", 0)
        val lang2 = input.toLanguage(testId, 1, "v1", 5)
        assertEquals(0, lang1.sort)
        assertEquals(5, lang2.sort)
    }

    @Test
    fun `data class copy preserves fields`() {
        val input = BibleLanguageInput(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val copied = input.copy(iso = "fra", name = "French")
        assertEquals("fra", copied.iso)
        assertEquals("French", copied.name)
        assertEquals("English", copied.nameLocal)
        assertEquals("Latin", copied.script)
    }
}
