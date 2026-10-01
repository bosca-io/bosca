package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleLanguageTest {

    @Test
    fun fieldPreservation() {
        val language = BibleLanguage(
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        assertEquals("eng", language.iso)
        assertEquals("English", language.name)
        assertEquals("English", language.nameLocal)
        assertEquals("Latin", language.script)
        assertEquals("Latn", language.scriptCode)
        assertEquals("LTR", language.scriptDirection)
    }

    @Test
    fun asSerializablePreservesAllFields() {
        val language = BibleLanguage(
            iso = "spa",
            name = "Spanish",
            nameLocal = "Espanol",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "LTR"
        )
        val serializable = language.asSerializable()
        assertEquals("spa", serializable.iso)
        assertEquals("Spanish", serializable.name)
        assertEquals("Espanol", serializable.nameLocal)
    }

    @Test
    fun interfaceAsSerializableUsesNameWhenNameLocalIsNull() {
        val language = object : IBibleLanguage {
            override val iso = "heb"
            override val name = "Hebrew"
            override val nameLocal: String? = null
            override val script = "Hebrew"
            override val scriptCode = "Hebr"
            override val scriptDirection = "RTL"
        }
        val serializable = language.asSerializable()
        assertEquals("Hebrew", serializable.nameLocal)
    }

    @Test
    fun interfaceAsSerializableUsesNameLocalWhenPresent() {
        val language = object : IBibleLanguage {
            override val iso = "heb"
            override val name = "Hebrew"
            override val nameLocal = "Ivrit"
            override val script = "Hebrew"
            override val scriptCode = "Hebr"
            override val scriptDirection = "RTL"
        }
        val serializable = language.asSerializable()
        assertEquals("Ivrit", serializable.nameLocal)
    }
}
