package bosca.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransformationsTest {

    @Test
    fun `MetadataDocumentToTextProvider has expected value`() {
        assertEquals("metadataDocumentToTextTransformation", MetadataDocumentToTextProvider)
    }

    @Test
    fun `DocumentToTextProvider has expected value`() {
        assertEquals("documentToTextTransformation", DocumentToTextProvider)
    }

    @Test
    fun `provider constants are distinct`() {
        assertTrue(MetadataDocumentToTextProvider != DocumentToTextProvider)
    }
}
