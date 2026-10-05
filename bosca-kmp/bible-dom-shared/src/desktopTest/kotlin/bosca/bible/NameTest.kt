package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals

class NameTest {

    @Test
    fun nameFieldsArePreserved() {
        val name = Name(id = "GEN", long = "Genesis", short = "Gen", abbreviation = "Ge")
        assertEquals("GEN", name.id)
        assertEquals("Genesis", name.long)
        assertEquals("Gen", name.short)
        assertEquals("Ge", name.abbreviation)
    }

    @Test
    fun asSerializableReturnsSelf() {
        val name = Name(id = "PSA", long = "Psalms", short = "Psa", abbreviation = "Ps")
        val serializable = name.asSerializable()
        assertEquals(name, serializable)
    }

    @Test
    fun dataClassEquality() {
        val name1 = Name(id = "GEN", long = "Genesis", short = "Gen", abbreviation = "Ge")
        val name2 = Name(id = "GEN", long = "Genesis", short = "Gen", abbreviation = "Ge")
        assertEquals(name1, name2)
    }
}
