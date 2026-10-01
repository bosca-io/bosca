package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChapterTest {

    @Test
    fun getReturnsSelfComponentWhenReferenceMatches() {
        val ref = Reference("GEN.1")
        val chapter = Chapter(reference = ref, component = null)
        val result = chapter[ref]
        assertNull(result)
    }

    @Test
    fun getReturnsNullForNullComponentWithMatchingReference() {
        val ref = Reference("GEN.1")
        val chapter = Chapter(reference = ref, component = null)
        assertNull(chapter[ref])
    }

    @Test
    fun referenceIsPreserved() {
        val ref = Reference("PSA.23")
        val chapter = Chapter(reference = ref, component = null)
        assertEquals(ref, chapter.reference)
    }

    @Test
    fun asSerializableReturnsChapter() {
        val ref = Reference("GEN.1")
        val chapter = Chapter(reference = ref, component = null)
        val serializable = chapter.asSerializable()
        assertNotNull(serializable)
        assertEquals(ref, serializable.reference)
    }
}
