package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SetCollectionStatusJobTest {

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val job = SetCollectionStatusJob(
            id = id,
            public = true,
            publicList = false,
            publicSupplementary = true,
            languageTag = "es",
            type = "variant"
        )
        assertEquals(id, job.id)
        assertTrue(job.public!!)
        assertFalse(job.publicList!!)
        assertTrue(job.publicSupplementary!!)
        assertEquals("es", job.languageTag)
        assertEquals("variant", job.type)
    }

    @Test
    fun `languageTag defaults to null`() {
        val id = UUID.random()
        val job = SetCollectionStatusJob(
            id = id,
            public = null,
            publicList = null,
            publicSupplementary = null
        )
        assertNull(job.languageTag)
    }

    @Test
    fun `type defaults to collection`() {
        val id = UUID.random()
        val job = SetCollectionStatusJob(
            id = id,
            public = null,
            publicList = null,
            publicSupplementary = null
        )
        assertEquals("collection", job.type)
    }

    @Test
    fun `nullable boolean fields can be null`() {
        val id = UUID.random()
        val job = SetCollectionStatusJob(
            id = id,
            public = null,
            publicList = null,
            publicSupplementary = null
        )
        assertNull(job.public)
        assertNull(job.publicList)
        assertNull(job.publicSupplementary)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = SetCollectionStatusJob(id = id, public = true, publicList = false, publicSupplementary = null)
        val b = SetCollectionStatusJob(id = id, public = true, publicList = false, publicSupplementary = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes public field`() {
        val id = UUID.random()
        val job = SetCollectionStatusJob(id = id, public = false, publicList = null, publicSupplementary = null)
        val modified = job.copy(public = true)
        assertTrue(modified.public!!)
        assertEquals(id, modified.id)
    }
}
