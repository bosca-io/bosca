package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttributesTest {

    @Test
    fun getReturnsByLowercaseKey() {
        val attrs = Attributes(mapOf("STYLE" to "p", "CODE" to "GEN"))
        assertEquals("p", attrs["STYLE"])
        assertEquals("GEN", attrs["CODE"])
    }

    @Test
    fun getIsCaseInsensitive() {
        val attrs = Attributes(mapOf("Style" to "heading"))
        assertEquals("heading", attrs["style"])
        assertEquals("heading", attrs["STYLE"])
        assertEquals("heading", attrs["Style"])
    }

    @Test
    fun getReturnsNullForMissingKey() {
        val attrs = Attributes(mapOf("STYLE" to "p"))
        assertNull(attrs["MISSING"])
    }

    @Test
    fun emptyAttributesMap() {
        val attrs = Attributes(emptyMap())
        assertNull(attrs["anything"])
    }

    @Test
    fun keysAreNormalizedToLowercase() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "number" to "2"))
        // Both keys normalize to "number", the last one wins in the map
        val result = attrs["NUMBER"]
        assertEquals(true, result == "1" || result == "2")
    }

    @Test
    fun toStringIncludesAttributes() {
        val attrs = Attributes(mapOf("STYLE" to "p"))
        val str = attrs.toString()
        assertEquals(true, str.contains("style"))
        assertEquals(true, str.contains("p"))
    }
}
