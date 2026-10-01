package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleIdentificationTest {

    @Test
    fun fieldPreservation() {
        val system = BibleSystem(id = "paratext")
        val identification = BibleIdentification(
            system = system,
            name = "English Standard Version",
            nameLocal = "ESV",
            description = "A literal translation",
            abbreviation = "ESV",
            abbreviationLocal = "ESV"
        )
        assertEquals(system, identification.system)
        assertEquals("English Standard Version", identification.name)
        assertEquals("ESV", identification.nameLocal)
        assertEquals("A literal translation", identification.description)
        assertEquals("ESV", identification.abbreviation)
        assertEquals("ESV", identification.abbreviationLocal)
    }

    @Test
    fun asSerializablePreservesAllFields() {
        val system = BibleSystem(id = "sys")
        val identification = BibleIdentification(
            system = system,
            name = "Test Bible",
            nameLocal = "TB",
            description = "desc",
            abbreviation = "TB",
            abbreviationLocal = "TBL"
        )
        val serializable = identification.asSerializable()
        assertEquals("Test Bible", serializable.name)
        assertEquals("TB", serializable.nameLocal)
        assertEquals("desc", serializable.description)
        assertEquals("TB", serializable.abbreviation)
        assertEquals("TBL", serializable.abbreviationLocal)
        assertEquals("sys", serializable.system.id)
    }
}
