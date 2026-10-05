package bosca.content.collection.jobs

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionDeleteFromIndexJobTest {

    @Test
    fun `default values are null`() {
        val job = CollectionDeleteFromIndexJob()
        assertNull(job.id)
        assertNull(job.languageTag)
        assertNull(job.storage)
    }

    @Test
    fun `job with id only`() {
        val id = UUID.random()
        val job = CollectionDeleteFromIndexJob(id = id)
        assertEquals(id, job.id)
        assertNull(job.languageTag)
        assertNull(job.storage)
    }

    @Test
    fun `job with id and languageTag for variant deletion`() {
        val id = UUID.random()
        val job = CollectionDeleteFromIndexJob(id = id, languageTag = "es")
        assertEquals(id, job.id)
        assertEquals("es", job.languageTag)
        assertNull(job.storage)
    }
}
