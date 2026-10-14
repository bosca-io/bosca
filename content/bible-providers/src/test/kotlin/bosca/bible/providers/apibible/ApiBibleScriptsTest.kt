package bosca.bible.providers.apibible

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiBibleScriptsTest {

    @Test
    fun `script names and codes resolve to ISO 15924 codes`() {
        for ((script, code) in listOf(
            "Latin" to "Latn", "latn" to "Latn", " Cyrillic " to "Cyrl", "Syriac" to "Syrc",
            "Canadian Aboriginal" to "Cans", "Thaana" to "Thaa", "N'Ko" to "Nkoo", "Mongolian" to "Mong",
            "Han (Simplified variant)" to "Hans", "Traditional Han" to "Hant", "Ethiopic" to "Ethi",
        )) {
            assertEquals(code, resolveScriptCode(script), script)
        }
    }

    @Test
    fun `unrecognized scripts resolve to the unknown script code instead of failing`() {
        assertEquals(UNKNOWN_SCRIPT_CODE, resolveScriptCode("Not a script"))
        assertEquals("Zzzz", ApiBibleLanguage("xxx", "Unknown", null, "Not a script", "LTR").scriptCode)
    }
}
