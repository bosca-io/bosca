package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleReference
import kotlin.test.Test
import kotlin.test.assertEquals

class BibleReferenceControllerTest {

    private val controller = BibleReferenceController()

    @Test
    fun `usfm returns usfm reference string`() {
        val reference = BibleReference(usfm = "GEN.1.1", human = "Genesis 1:1", humanShort = "Gen 1:1")

        assertEquals("GEN.1.1", controller.usfm(reference))
    }

    @Test
    fun `human returns human readable reference`() {
        val reference = BibleReference(usfm = "GEN.1.1", human = "Genesis 1:1", humanShort = "Gen 1:1")

        assertEquals("Genesis 1:1", controller.human(reference))
    }

    @Test
    fun `humanShort returns short human readable reference`() {
        val reference = BibleReference(usfm = "GEN.1.1", human = "Genesis 1:1", humanShort = "Gen 1:1")

        assertEquals("Gen 1:1", controller.humanShort(reference))
    }
}
