package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import java.time.OffsetDateTime

class MetadataSupplementaryTest {

    private val metaId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val suppId = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")

    @Test
    fun fieldsArePreserved() {
        val now = OffsetDateTime.now()
        val supp = MetadataSupplementary(
            id = suppId,
            metadataId = metaId,
            key = "thumbnail",
            name = "Thumbnail Image",
            created = now,
            modified = now,
            contentType = "image/png",
            contentLength = 1024
        )
        assertEquals(suppId, supp.id)
        assertEquals(metaId, supp.metadataId)
        assertEquals("thumbnail", supp.key)
        assertEquals("Thumbnail Image", supp.name)
        assertEquals(now, supp.created)
        assertEquals(now, supp.modified)
        assertEquals("image/png", supp.contentType)
        assertEquals(1024L, supp.contentLength)
    }

    @Test
    fun defaultValues() {
        val now = OffsetDateTime.now()
        val supp = MetadataSupplementary(
            metadataId = metaId,
            key = "k",
            name = "n",
            created = now,
            modified = now
        )
        assertEquals(Uuid.NIL, supp.id)
        assertNull(supp.planId)
        assertNull(supp.jobId)
        assertNull(supp.attributes)
        assertNull(supp.uploaded)
        assertNull(supp.contentType)
        assertNull(supp.contentLength)
        assertNull(supp.sourceId)
        assertNull(supp.sourceIdentifier)
    }

    @Test
    fun toIdCreatesSupplementaryIdObject() {
        val now = OffsetDateTime.now()
        val planId = Uuid.parse("770e8400-e29b-41d4-a716-446655440002")
        val supp = MetadataSupplementary(
            id = suppId,
            metadataId = metaId,
            key = "audio",
            name = "Audio",
            planId = planId,
            created = now,
            modified = now
        )
        val idObj = supp.toId()
        assertEquals(metaId, idObj.contentId)
        assertEquals(suppId, idObj.id)
        assertEquals("audio", idObj.key)
        assertEquals(planId, idObj.planId)
    }
}
