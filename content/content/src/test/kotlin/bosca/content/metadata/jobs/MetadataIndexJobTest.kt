package bosca.content.metadata.jobs

import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MetadataIndexJobTest {

    @Test
    fun `defaults are null or false`() {
        val job = MetadataIndexJob()
        assertNull(job.id)
        assertNull(job.version)
        assertNull(job.storage)
        assertFalse(job.deleteFirst)
        assertFalse(job.deleteOnly)
        assertNull(job.batchSize)
    }

    @Test
    fun `field preservation with all values`() {
        val id = UUID.random()
        val storage = IndexStorageSystem(id = UUID.random(), name = "es")
        val job = MetadataIndexJob(
            id = id,
            version = 2,
            storage = storage,
            deleteFirst = true,
            deleteOnly = true,
            batchSize = 100
        )
        assertEquals(id, job.id)
        assertEquals(2, job.version)
        assertEquals(storage, job.storage)
        assertEquals(true, job.deleteFirst)
        assertEquals(true, job.deleteOnly)
        assertEquals(100, job.batchSize)
    }

    @Test
    fun `data class equality`() {
        val id = UUID.random()
        val a = MetadataIndexJob(id = id, version = 1, deleteFirst = true)
        val b = MetadataIndexJob(id = id, version = 1, deleteFirst = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy changes batchSize`() {
        val job = MetadataIndexJob(batchSize = 50)
        val modified = job.copy(batchSize = 200)
        assertEquals(200, modified.batchSize)
        assertNull(modified.id)
    }
}
