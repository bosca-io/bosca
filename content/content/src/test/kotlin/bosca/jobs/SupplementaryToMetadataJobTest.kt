package bosca.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupplementaryToMetadataJobTest {

    @Test
    fun `defaults are null`() {
        val job = SupplementaryToMetadataJob()
        assertNull(job.supplementaryId)
        assertNull(job.relationship)
    }

    @Test
    fun `field preservation with all values`() {
        val suppId = UUID.random()
        val job = SupplementaryToMetadataJob(supplementaryId = suppId, relationship = "audio")
        assertEquals(suppId, job.supplementaryId)
        assertEquals("audio", job.relationship)
    }

    @Test
    fun `data class equality`() {
        val suppId = UUID.random()
        val a = SupplementaryToMetadataJob(supplementaryId = suppId, relationship = "rel")
        val b = SupplementaryToMetadataJob(supplementaryId = suppId, relationship = "rel")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes relationship`() {
        val suppId = UUID.random()
        val job = SupplementaryToMetadataJob(supplementaryId = suppId, relationship = "old")
        val modified = job.copy(relationship = "new")
        assertEquals("new", modified.relationship)
        assertEquals(suppId, modified.supplementaryId)
    }

    @Test
    fun `only supplementaryId set`() {
        val suppId = UUID.random()
        val job = SupplementaryToMetadataJob(supplementaryId = suppId)
        assertEquals(suppId, job.supplementaryId)
        assertNull(job.relationship)
    }
}
