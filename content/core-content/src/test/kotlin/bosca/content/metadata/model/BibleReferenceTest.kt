package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BibleReferenceTest {

    @Test
    fun `BibleReference stores all properties`() {
        val ref = BibleReference(
            usfm = "GEN.1.1",
            human = "Genesis 1:1",
            humanShort = "Gen 1:1"
        )
        assertEquals("GEN.1.1", ref.usfm)
        assertEquals("Genesis 1:1", ref.human)
        assertEquals("Gen 1:1", ref.humanShort)
    }
}
