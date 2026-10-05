package bosca.bible.usx

import bosca.bible.Reference
import bosca.bible.VerseStartStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VerseStartTest {

    private fun createVerseStart(
        number: String = "1",
        sid: String = "GEN 1:1",
        altNumber: String? = null,
        pubNumber: String? = null,
        ref: Reference? = Reference("GEN.1.1")
    ): VerseStart {
        val map = mutableMapOf("STYLE" to "v", "NUMBER" to number, "SID" to sid)
        altNumber?.let { map["ALTNUMBER"] = it }
        pubNumber?.let { map["PUBNUMBER"] = it }
        return VerseStart(Attributes(map), ref, Position(0))
    }

    @Test
    fun fieldPreservation() {
        val vs = createVerseStart(number = "5", sid = "GEN 1:5")
        assertEquals(VerseStartStyle.v, vs.style)
        assertEquals("5", vs.number)
        assertEquals("GEN 1:5", vs.sid)
    }

    @Test
    fun altNumberIsNullWhenMissing() {
        val vs = createVerseStart()
        assertNull(vs.altNumber)
    }

    @Test
    fun altNumberPreservedWhenPresent() {
        val vs = createVerseStart(altNumber = "1a")
        assertEquals("1a", vs.altNumber)
    }

    @Test
    fun altNumberNullWhenEmpty() {
        val vs = createVerseStart(altNumber = "")
        assertNull(vs.altNumber)
    }

    @Test
    fun pubNumberIsNullWhenMissing() {
        val vs = createVerseStart()
        assertNull(vs.pubNumber)
    }

    @Test
    fun pubNumberPreservedWhenPresent() {
        val vs = createVerseStart(pubNumber = "I")
        assertEquals("I", vs.pubNumber)
    }

    @Test
    fun pubNumberNullWhenEmpty() {
        val vs = createVerseStart(pubNumber = "")
        assertNull(vs.pubNumber)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(mapOf("NUMBER" to "1", "SID" to "GEN 1:1"))
        assertFailsWith<IllegalStateException> {
            VerseStart(attrs, null, Position(0))
        }
    }

    @Test
    fun missingNumberThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "v", "SID" to "GEN 1:1"))
        assertFailsWith<IllegalStateException> {
            VerseStart(attrs, null, Position(0))
        }
    }

    @Test
    fun missingSidThrowsError() {
        val attrs = Attributes(mapOf("STYLE" to "v", "NUMBER" to "1"))
        assertFailsWith<IllegalStateException> {
            VerseStart(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassIsStyleName() {
        val vs = createVerseStart()
        assertEquals("v", vs.htmlClass)
    }

    @Test
    fun htmlAttributesContainUsfmAndVerse() {
        val vs = createVerseStart(number = "3", ref = Reference("GEN.1.3"))
        val attrs = vs.htmlAttributes
        assertEquals("GEN.1.3", attrs["data-usfm"])
        assertEquals("3", attrs["data-verse"])
    }

    @Test
    fun toStringWithVerseNumbers() {
        val vs = createVerseStart(number = "7")
        val result = vs.toString(StringContext(includeVerseNumbers = true))
        assertEquals("7. ", result)
    }

    @Test
    fun toStringWithoutVerseNumbers() {
        val vs = createVerseStart(number = "7")
        val result = vs.toString(StringContext(includeVerseNumbers = false))
        assertEquals("", result)
    }
}
