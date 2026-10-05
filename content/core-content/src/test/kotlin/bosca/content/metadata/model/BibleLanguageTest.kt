package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class BibleLanguageTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createLanguage(
        metadataId: Uuid = testId,
        version: Int = 1,
        variant: String = "default",
        iso: String = "eng",
        name: String = "English",
        nameLocal: String = "English",
        script: String = "Latin",
        scriptCode: String = "Latn",
        scriptDirection: String = "LTR",
        sort: Int = 1
    ) = BibleLanguage(
        metadataId = metadataId,
        version = version,
        variant = variant,
        iso = iso,
        name = name,
        nameLocal = nameLocal,
        script = script,
        scriptCode = scriptCode,
        scriptDirection = scriptDirection,
        sort = sort
    )

    @Test
    fun `field preservation after construction`() {
        val lang = createLanguage()
        assertEquals(testId, lang.metadataId)
        assertEquals(1, lang.version)
        assertEquals("default", lang.variant)
        assertEquals("eng", lang.iso)
        assertEquals("English", lang.name)
        assertEquals("English", lang.nameLocal)
        assertEquals("Latin", lang.script)
        assertEquals("Latn", lang.scriptCode)
        assertEquals("LTR", lang.scriptDirection)
        assertEquals(1, lang.sort)
    }

    @Test
    fun `RTL script direction is preserved`() {
        val lang = createLanguage(
            iso = "ara",
            name = "Arabic",
            nameLocal = "العربية",
            script = "Arabic",
            scriptCode = "Arab",
            scriptDirection = "RTL"
        )
        assertEquals("RTL", lang.scriptDirection)
        assertEquals("العربية", lang.nameLocal)
    }

    @Test
    fun `different sort values are preserved`() {
        val lang1 = createLanguage(sort = 0)
        val lang2 = createLanguage(sort = 100)
        assertEquals(0, lang1.sort)
        assertEquals(100, lang2.sort)
    }
}
