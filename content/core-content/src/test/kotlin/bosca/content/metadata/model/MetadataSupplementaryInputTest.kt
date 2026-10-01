package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MetadataSupplementaryInputTest {

    private val metaId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val input = MetadataSupplementaryInput(
            metadataId = metaId,
            key = "thumbnail",
            name = "Thumbnail",
            contentType = "image/png",
            contentLength = 2048
        )
        assertEquals(metaId, input.metadataId)
        assertEquals("thumbnail", input.key)
        assertEquals("Thumbnail", input.name)
        assertEquals("image/png", input.contentType)
        assertEquals(2048L, input.contentLength)
    }

    @Test
    fun defaultsAreNull() {
        val input = MetadataSupplementaryInput(
            metadataId = metaId,
            key = "k",
            name = "n",
            contentType = "text/plain"
        )
        assertNull(input.attributes)
        assertNull(input.planId)
        assertNull(input.jobId)
        assertNull(input.contentLength)
        assertNull(input.sourceId)
        assertNull(input.sourceIdentifier)
    }

    @Test
    fun dataClassEquality() {
        val a = MetadataSupplementaryInput(metadataId = metaId, key = "k", name = "n", contentType = "ct")
        val b = MetadataSupplementaryInput(metadataId = metaId, key = "k", name = "n", contentType = "ct")
        assertEquals(a, b)
    }
}
