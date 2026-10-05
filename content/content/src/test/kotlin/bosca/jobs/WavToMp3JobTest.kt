package bosca.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WavToMp3JobTest {

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val suppId = UUID.random()
        val job = WavToMp3Job(id = id, version = 2, supplementaryId = suppId)
        assertEquals(id, job.id)
        assertEquals(2, job.version)
        assertEquals(suppId, job.supplementaryId)
    }

    @Test
    fun `supplementaryId defaults to null`() {
        val id = UUID.random()
        val job = WavToMp3Job(id = id, version = 1)
        assertNull(job.supplementaryId)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = WavToMp3Job(id = id, version = 1)
        val b = WavToMp3Job(id = id, version = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes supplementaryId`() {
        val id = UUID.random()
        val job = WavToMp3Job(id = id, version = 1)
        val suppId = UUID.random()
        val modified = job.copy(supplementaryId = suppId)
        assertEquals(suppId, modified.supplementaryId)
        assertEquals(id, modified.id)
    }
}
