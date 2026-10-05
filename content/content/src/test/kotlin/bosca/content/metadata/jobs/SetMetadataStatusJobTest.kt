package bosca.content.metadata.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SetMetadataStatusJobTest {

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val job = SetMetadataStatusJob(
            id = id,
            version = 2,
            public = true,
            publicContent = false,
            publicSupplementary = true,
            type = "custom"
        )
        assertEquals(id, job.id)
        assertEquals(2, job.version)
        assertTrue(job.public!!)
        assertFalse(job.publicContent!!)
        assertTrue(job.publicSupplementary!!)
        assertEquals("custom", job.type)
    }

    @Test
    fun `type defaults to metadata`() {
        val id = UUID.random()
        val job = SetMetadataStatusJob(
            id = id,
            version = 1,
            public = null,
            publicContent = null,
            publicSupplementary = null
        )
        assertEquals("metadata", job.type)
    }

    @Test
    fun `nullable boolean fields can be null`() {
        val id = UUID.random()
        val job = SetMetadataStatusJob(
            id = id,
            version = 1,
            public = null,
            publicContent = null,
            publicSupplementary = null
        )
        assertNull(job.public)
        assertNull(job.publicContent)
        assertNull(job.publicSupplementary)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = SetMetadataStatusJob(id = id, version = 1, public = true, publicContent = false, publicSupplementary = null)
        val b = SetMetadataStatusJob(id = id, version = 1, public = true, publicContent = false, publicSupplementary = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes public field`() {
        val id = UUID.random()
        val job = SetMetadataStatusJob(id = id, version = 1, public = false, publicContent = null, publicSupplementary = null)
        val modified = job.copy(public = true)
        assertTrue(modified.public!!)
        assertEquals(id, modified.id)
    }
}
