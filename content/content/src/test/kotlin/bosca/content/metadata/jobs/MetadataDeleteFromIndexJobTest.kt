package bosca.content.metadata.jobs

import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataDeleteFromIndexJobTest {

    @Test
    fun `defaults are all null`() {
        val job = MetadataDeleteFromIndexJob()
        assertNull(job.id)
        assertNull(job.version)
        assertNull(job.storage)
    }

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val storage = IndexStorageSystem(id = UUID.random(), name = "meilisearch")
        val job = MetadataDeleteFromIndexJob(id = id, version = 3, storage = storage)
        assertEquals(id, job.id)
        assertEquals(3, job.version)
        assertEquals(storage, job.storage)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = MetadataDeleteFromIndexJob(id = id, version = 1)
        val b = MetadataDeleteFromIndexJob(id = id, version = 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes version`() {
        val id = UUID.random()
        val job = MetadataDeleteFromIndexJob(id = id, version = 1)
        val modified = job.copy(version = 5)
        assertEquals(id, modified.id)
        assertEquals(5, modified.version)
    }
}
